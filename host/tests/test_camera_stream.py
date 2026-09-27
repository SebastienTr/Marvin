# SPDX-License-Identifier: MIT
"""CameraStream and MjpegParser against a local stdlib HTTP server that serves MJPEG like the robot."""
import io
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from marvin_host import camera_stream
from marvin_host.camera_stream import CameraStream, MjpegParser, boundary_of


def tiny_jpeg(value: int) -> bytes:
    """A real 8x8 JPEG if Pillow is there, otherwise JPEG-shaped bytes (SOI ... EOI)."""
    try:
        from PIL import Image
    except ImportError:
        return b"\xff\xd8" + bytes([value]) * 32 + b"\xff\xd9"
    buf = io.BytesIO()
    Image.new("RGB", (8, 8), (value, 255 - value, 0)).save(buf, format="JPEG")
    return buf.getvalue()


JPEGS = [tiny_jpeg(v) for v in (10, 120, 240)]
BOUNDARY = "marvinframe"


def multipart(jpegs, *, lengths=True, stamps=True, boundary=BOUNDARY, closing=False) -> bytes:
    out = b""
    for i, j in enumerate(jpegs):
        head = f"--{boundary}\r\nContent-Type: image/jpeg\r\n"
        if lengths:
            head += f"Content-Length: {len(j)}\r\n"
        if stamps:
            head += f"X-Marvin-Time-Us: {1000 * (i + 1)}\r\n"
        out += head.encode() + b"\r\n" + j + b"\r\n"
    if closing:
        out += f"--{boundary}--\r\n".encode()
    return out


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    requests: list = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        Handler.requests.append(self.path)
        if self.path == "/stream":
            self.send_response(200)
            self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY}")
            self.end_headers()
            delim = f"--{BOUNDARY}".encode()
            for part in multipart(JPEGS).split(delim)[1:]:   # three frames, then the server hangs up
                self.wfile.write(delim + part)
                self.wfile.flush()
                time.sleep(0.01)
        elif self.path.startswith("/capture"):
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(JPEGS[0])))
            self.send_header("X-Marvin-Time-Us", "42")
            self.end_headers()
            self.wfile.write(JPEGS[0])
        else:
            self.send_error(404)


@pytest.fixture
def server():
    Handler.requests = []
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()
    srv.server_close()


# ---- Parser -----------------------------------------------------------------------------------

def test_boundary_of():
    assert boundary_of("multipart/x-mixed-replace; boundary=marvinframe") == b"marvinframe"
    assert boundary_of('multipart/x-mixed-replace;boundary="--frame"') == b"frame"
    assert boundary_of("multipart/x-mixed-replace") is None


@pytest.mark.parametrize("lengths", [True, False])
def test_parser_every_split(lengths):
    data = b"garbage before the first part\r\n" + multipart(JPEGS, lengths=lengths) + b"--" + BOUNDARY.encode()
    for step in (1, 2, 7, 64, len(data)):
        p = MjpegParser(BOUNDARY.encode())
        parts = []
        for i in range(0, len(data), step):
            parts += p.feed(data[i:i + step])
        assert [b for _, b in parts] == JPEGS, step
        assert [h["x-marvin-time-us"] for h, _ in parts] == ["1000", "2000", "3000"]


def test_parser_closing_boundary_and_lf_only():
    data = multipart(JPEGS[:2], closing=True).replace(b"\r\n", b"\n")
    p = MjpegParser(BOUNDARY.encode())
    # LF-only framing with Content-Length: bodies are exact
    parts = p.feed(data)
    assert [b for _, b in parts] == JPEGS[:2]
    assert p.done


def test_parser_without_boundary_cuts_on_jpeg_markers():
    p = MjpegParser(None)
    parts = []
    stream = b"xx" + JPEGS[0] + b"\r\n--whatever\r\n\r\n" + JPEGS[1]
    for i in range(0, len(stream), 5):
        parts += p.feed(stream[i:i + 5])
    assert [b[:2] for _, b in parts] == [b"\xff\xd8"] * 2
    assert [b[-2:] for _, b in parts] == [b"\xff\xd9"] * 2


# ---- Stream over HTTP -------------------------------------------------------------------------

def test_stream_frames_and_reconnect(server):
    got = []
    cam = CameraStream(server + "/stream", backoff=(0.05, 0.2), keep=16)
    with cam:
        for f in cam.frames(timeout=5):
            got.append(f)
            if len(got) == 6:
                break
    assert [f.jpeg for f in got] == JPEGS + JPEGS          # the server hung up after 3: reconnected
    assert [f.robot_t_us for f in got[:3]] == [1000, 2000, 3000]
    assert [f.index for f in got] == list(range(6))
    assert cam.stats.connects >= 2
    assert Handler.requests.count("/stream") >= 2
    assert cam.latest() is not None


def test_callback_and_decode(server):
    seen = threading.Event()
    frames = []

    def on_frame(f):
        frames.append(f)
        seen.set()

    cam = CameraStream(server + "/stream", on_frame=on_frame, backoff=(0.05, 0.2)).start()
    assert seen.wait(5)
    cam.stop()
    img = frames[0].decode()
    try:
        import PIL  # noqa: F401
    except ImportError:
        assert img is None
    else:
        assert img.shape == (8, 8, 3)


def test_capture_url_polls_single_images(server):
    assert camera_stream.capture(server + "/capture") == JPEGS[0]
    cam = CameraStream(server + "/capture", poll_interval=0.01)
    with cam:
        got = []
        for f in cam.frames(timeout=5):
            got.append(f)
            if len(got) == 2:
                break
    assert got[0].jpeg == JPEGS[0] and got[0].robot_t_us == 42


def test_errors_back_off_and_stop_is_prompt(server):
    cam = CameraStream(server + "/nope", backoff=(0.05, 0.1)).start()
    time.sleep(0.4)
    t = time.monotonic()
    cam.stop()
    assert time.monotonic() - t < 1.0
    assert cam.stats.errors >= 2 and cam.stats.frames == 0
    assert "404" in cam.stats.last_error


def test_unreachable_host_retries():
    cam = CameraStream("http://127.0.0.1:9/stream", timeout=0.2, backoff=(0.05, 0.1)).start()
    time.sleep(0.3)
    cam.stop()
    assert cam.stats.errors >= 1
    assert list(cam.frames(timeout=0.1)) == []


def test_rejects_non_http_url():
    with pytest.raises(ValueError):
        CameraStream("rtsp://robot/stream")

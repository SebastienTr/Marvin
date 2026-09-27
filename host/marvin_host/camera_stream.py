"""The robot's camera: an MJPEG-over-HTTP client (standard library only, Pillow optional).

The robot serves `http://<ip>:81/stream` (multipart/x-mixed-replace, one JPEG per part, each with
Content-Length and X-Marvin-Time-Us, the robot clock of the UDP headers) and `/capture` (one
JPEG); see docs/protocol.md. The host learns the robot's IP from its HELLO, and the HELLO camera
flag says whether there is a camera: `protocol.camera_url(dev.addr[0])`.

    with CameraStream(url) as cam:           # background thread, reconnects with backoff
        for frame in cam.frames():           # Frame(jpeg, t, robot_t_us, index)
            rr.log("camera", rr.EncodedImage(contents=frame.jpeg, media_type="image/jpeg"))

or with a callback: `CameraStream(url, on_frame=fn).start()`. Decoding is optional: Rerun logs
the JPEG as is; `frame.decode()` gives an RGB numpy array when Pillow is installed.

The multipart parser (`MjpegParser`) uses each part's Content-Length when there is one and
otherwise looks for the next boundary; a stream without a usable boundary is cut on JPEG start
and end markers. It survives garbage, missing headers and parts split across reads.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import http.client
import io
import logging
import queue
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Callable, Iterator

import numpy as np

log = logging.getLogger("marvin.camera")

_SOI, _EOI = b"\xff\xd8", b"\xff\xd9"
MAX_PART = 4 << 20            # a part larger than this is garbage: resynchronise


@dataclass
class Frame:
    jpeg: bytes
    t: float                  # host time.monotonic() when the frame was complete
    robot_t_us: int | None    # robot clock at capture (X-Marvin-Time-Us), if the server sent it
    index: int                # frames received by this CameraStream so far

    def decode(self) -> np.ndarray | None:
        return decode_jpeg(self.jpeg)


def decode_jpeg(jpeg: bytes) -> np.ndarray | None:
    """RGB uint8 (h, w, 3), or None if Pillow is missing or the JPEG is broken."""
    try:
        from PIL import Image
    except ImportError:
        return None
    try:
        with Image.open(io.BytesIO(jpeg)) as im:
            return np.asarray(im.convert("RGB"))
    except Exception:
        return None


def boundary_of(content_type: str) -> bytes | None:
    """The multipart boundary from a Content-Type header, without quotes or leading dashes."""
    for param in content_type.split(";")[1:]:
        key, _, value = param.strip().partition("=")
        if key.strip().lower() == "boundary":
            value = value.strip().strip('"')
            while value.startswith("--"):
                value = value[2:]
            return value.encode() or None
    return None


class MjpegParser:
    """Incremental multipart/x-mixed-replace parser: feed() bytes, get (headers, body) parts."""

    def __init__(self, boundary: bytes | None):
        self.delim = b"--" + boundary if boundary else None
        self.buf = bytearray()
        self.headers: dict[str, str] | None = None     # headers of the part being read
        self.done = False                               # the closing boundary was seen

    def feed(self, data: bytes) -> list[tuple[dict[str, str], bytes]]:
        self.buf += data
        out: list[tuple[dict[str, str], bytes]] = []
        while True:
            part = self._next() if self.delim else self._next_jpeg()
            if part is None:
                break
            out.append(part)
        if len(self.buf) > MAX_PART:                   # no progress for too long: resynchronise
            log.debug("dropping %d bytes of unparseable stream", len(self.buf))
            self.buf.clear()
            self.headers = None
        return out

    def _next(self):
        if self.headers is None:
            i = self.buf.find(self.delim)
            if i < 0:
                keep = len(self.delim) + 1                # a delimiter may be split across reads
                if len(self.buf) > keep:
                    del self.buf[:len(self.buf) - keep]
                return None
            j = self.buf.find(b"\n", i)
            if j < 0:
                return None
            line = bytes(self.buf[i + len(self.delim):j]).strip()
            if line.startswith(b"--"):                  # closing delimiter
                self.done = True
                del self.buf[:j + 1]
                return None
            end = self.buf.find(b"\r\n\r\n", j - 1)
            sep = 4
            alt = self.buf.find(b"\n\n", j - 1)
            if alt >= 0 and (end < 0 or alt < end):
                end, sep = alt, 2
            if end < 0:
                return None
            self.headers = {}
            for raw in bytes(self.buf[j + 1:end]).split(b"\n"):
                key, colon, value = raw.decode("latin-1").partition(":")
                if colon:
                    self.headers[key.strip().lower()] = value.strip()
            del self.buf[:end + sep]
        length = self.headers.get("content-length")
        if length is not None and length.isdigit():
            n = int(length)
            if len(self.buf) < n:
                return None
            body = bytes(self.buf[:n])
            del self.buf[:n]
        else:
            i = self.buf.find(self.delim)
            if i < 0:
                return None
            body = bytes(self.buf[:i])
            for tail in (b"\r\n", b"\n"):               # the line break before the delimiter
                if body.endswith(tail):
                    body = body[:-len(tail)]
                    break
            del self.buf[:i]
        headers, self.headers = self.headers, None
        return headers, body

    def _next_jpeg(self):
        i = self.buf.find(_SOI)
        if i < 0:
            del self.buf[:max(0, len(self.buf) - 1)]
            return None
        j = self.buf.find(_EOI, i + 2)
        if j < 0:
            del self.buf[:i]
            return None
        body = bytes(self.buf[i:j + 2])
        del self.buf[:j + 2]
        return {}, body


def capture(url: str, timeout: float = 5.0) -> bytes:
    """One JPEG from `http://<ip>:81/capture` (or any URL serving image/jpeg)."""
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read()


@dataclass
class CameraStats:
    frames: int = 0
    connects: int = 0
    errors: int = 0
    dropped: int = 0          # frames not taken from frames() in time (only the newest are kept)
    last_error: str = ""
    connected: bool = False
    fps: float = 0.0
    _times: list = field(default_factory=list, repr=False)


class CameraStream:
    """Reads an MJPEG stream in a background thread; reconnects with exponential backoff."""

    def __init__(self, url: str, *, on_frame: Callable[[Frame], None] | None = None, timeout: float = 5.0,
                 backoff: tuple[float, float] = (0.5, 5.0), keep: int = 2, poll_interval: float = 0.1):
        u = urllib.parse.urlsplit(url)
        if u.scheme != "http" or not u.hostname:
            raise ValueError(f"not an http:// URL: {url}")
        self.url = url
        self._host, self._port = u.hostname, u.port or 80
        self._path = (u.path or "/") + (f"?{u.query}" if u.query else "")
        self.on_frame = on_frame
        self.timeout = timeout
        self.backoff = backoff
        self.poll_interval = poll_interval       # between requests when the URL serves single JPEGs
        self.stats = CameraStats()
        self._q: queue.Queue = queue.Queue(maxsize=max(1, keep))
        self._stop = threading.Event()
        self._conn: http.client.HTTPConnection | None = None
        self._latest: Frame | None = None
        self._thread: threading.Thread | None = None

    # lifecycle

    def start(self) -> "CameraStream":
        if self._thread is None:
            self._thread = threading.Thread(target=self._run, name="camera-stream", daemon=True)
            self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        conn = self._conn
        if conn is not None:
            try:
                if conn.sock is not None:
                    conn.sock.shutdown(2)       # unblocks a read in progress
            except OSError:
                pass
        if self._thread is not None:
            self._thread.join(timeout=self.timeout + 1)
        try:
            self._q.put_nowait(None)
        except queue.Full:
            pass

    close = stop

    def __enter__(self) -> "CameraStream":
        return self.start()

    def __exit__(self, *exc) -> None:
        self.stop()

    # consumers

    def frames(self, timeout: float | None = None) -> Iterator[Frame]:
        """Yields frames as they arrive (the newest ones if the consumer is slow) until stop(),
        or until no frame came for `timeout` seconds."""
        self.start()
        while not self._stop.is_set():
            try:
                f = self._q.get(timeout=timeout if timeout is not None else 0.2)
            except queue.Empty:
                if timeout is not None:
                    return
                continue
            if f is None:
                return
            yield f

    def latest(self) -> Frame | None:
        return self._latest

    # internals

    def _deliver(self, jpeg: bytes, headers: dict[str, str]) -> None:
        if not jpeg:
            return
        t = time.monotonic()
        stamp = headers.get("x-marvin-time-us", "")
        f = Frame(jpeg, t, int(stamp) if stamp.isdigit() else None, self.stats.frames)
        s = self.stats
        s.frames += 1
        s._times = [x for x in s._times if t - x < 2.0] + [t]
        s.fps = (len(s._times) - 1) / (s._times[-1] - s._times[0]) if len(s._times) > 1 else 0.0
        self._latest = f
        if self.on_frame is not None:
            try:
                self.on_frame(f)
            except Exception:
                log.exception("camera on_frame callback failed")
        try:
            self._q.put_nowait(f)
        except queue.Full:
            try:
                self._q.get_nowait()
                s.dropped += 1
            except queue.Empty:
                pass
            self._q.put_nowait(f)

    def _session(self) -> tuple[bool, bool]:
        """One HTTP request: (delivered at least one frame, the URL serves single images)."""
        conn = http.client.HTTPConnection(self._host, self._port, timeout=self.timeout)
        self._conn = conn
        got = False
        try:
            conn.request("GET", self._path, headers={"Accept": "multipart/x-mixed-replace, image/jpeg"})
            resp = conn.getresponse()
            if resp.status != 200:
                raise OSError(f"HTTP {resp.status} {resp.reason}")
            self.stats.connects += 1
            self.stats.connected = True
            ctype = resp.getheader("Content-Type", "")
            if ctype.lower().startswith("image/"):
                self._deliver(resp.read(), {k.lower(): v for k, v in resp.getheaders()})
                return True, True
            parser = MjpegParser(boundary_of(ctype))
            while not self._stop.is_set():
                data = resp.read1(65536)
                if not data:
                    break                          # server closed the stream
                for headers, body in parser.feed(data):
                    self._deliver(body, headers)
                    got = True
                if parser.done:
                    break
            return got, False
        finally:
            self.stats.connected = False
            self._conn = None
            conn.close()

    def _run(self) -> None:
        delay = self.backoff[0]
        while not self._stop.is_set():
            got = single = False
            try:
                got, single = self._session()
            except (OSError, http.client.HTTPException, ValueError) as e:
                if not self._stop.is_set():
                    self.stats.errors += 1
                    self.stats.last_error = str(e)
                    log.debug("camera %s: %s", self.url, e)
            if self._stop.is_set():
                break
            if got:
                delay = self.backoff[0]
                self._stop.wait(self.poll_interval if single else 0.0)
            else:
                self._stop.wait(delay)
                delay = min(delay * 2, self.backoff[1])

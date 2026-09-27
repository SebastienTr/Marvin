# SPDX-License-Identifier: MIT
"""RobotMicSource and RobotSpeakerSink over real local UDP, against a fake robot."""
import socket
import threading
import time

import numpy as np
import pytest

from marvin_host import protocol
from marvin_host.audio import FRAME_SAMPLES, SAMPLE_RATE, AudioSink, AudioSource
from marvin_host.receiver import Receiver, Sink
from marvin_host.robot_audio import RobotMicSource, RobotSpeakerSink, audio_device, to_pcm16


class RecordingSink(Sink):
    def __init__(self):
        self.audio = []

    def on_audio(self, dev, t_us, seq, pcm):
        self.audio.append((t_us, seq, pcm))


class FakeRobot:
    """Says HELLO with the audio flag, sends AUDIO_IN, and records what the host sends."""

    def __init__(self, rx_port: int, flags=protocol.FLAG_AUDIO | protocol.FLAG_CAMERA):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.host = ("127.0.0.1", rx_port)
        self.seq = 0
        self.got = []                       # (monotonic time, header, payload)
        self._stop = threading.Event()
        hello = protocol.Hello(b"\x01\x02\x03\x04\x05\x06", 3, flags, -50, 1000, "test").encode()
        self.send(protocol.HELLO, hello)
        self.thread = threading.Thread(target=self._listen, daemon=True)
        self.thread.start()

    def send(self, msg_type, payload, t_us=0):
        self.sock.sendto(protocol.pack(msg_type, self.seq, t_us, payload), self.host)
        self.seq += 1

    def mic(self, index, pcm, t_us=0):
        self.send(protocol.AUDIO_IN, protocol.AudioIn(index, np.asarray(pcm, dtype=np.int16)).encode(), t_us)

    def _listen(self):
        self.sock.settimeout(0.05)
        while not self._stop.is_set():
            try:
                data, _ = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                return
            hdr, payload = protocol.unpack(data)
            self.got.append((time.monotonic(), hdr, payload))

    def of_type(self, t):
        return [(ts, h, p) for ts, h, p in list(self.got) if h.type == t]

    def wait_for(self, t, count=1, timeout=2.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if len(self.of_type(t)) >= count:
                return self.of_type(t)
            time.sleep(0.005)
        raise AssertionError(f"no {count} x {t:#x} from the host, got {[h.type for _, h, _ in self.got]}")

    def close(self):
        self._stop.set()
        self.thread.join()
        self.sock.close()


@pytest.fixture
def rig():
    sink = RecordingSink()
    rx = Receiver(sink, port=0, bind="127.0.0.1")
    stop = threading.Event()
    t = threading.Thread(target=rx.serve, kwargs={"stop": stop}, daemon=True)
    t.start()
    robot = FakeRobot(rx.sock.getsockname()[1])
    dev = audio_device(rx, timeout=2)
    assert dev is not None and dev.hello.has_audio and dev.hello.has_camera
    yield rx, robot, dev, sink
    stop.set()
    t.join()
    robot.close()
    rx.sock.close()


def ramp(start, n):
    return (np.arange(start, start + n) % 30000).astype(np.int16)


def ctrl(payload):
    return tuple(payload)


# ---- Protocol ---------------------------------------------------------------------------------

def test_hello_capability_flags_keep_old_meaning():
    h = protocol.Hello(b"\0" * 6, 3, protocol.FLAG_SIMULATED | protocol.FLAG_AUDIO, -40, 1, "x")
    h2 = protocol.Hello.decode(h.encode())
    assert h2.simulated and h2.has_audio and not h2.has_camera
    assert not protocol.Hello(b"\0" * 6, 1, 0, -40, 1, "x").has_audio
    assert protocol.camera_url("10.0.0.7") == "http://10.0.0.7:81/stream"
    assert protocol.camera_url("10.0.0.7", "/capture") == "http://10.0.0.7:81/capture"


def test_audio_payload_bytes():
    assert protocol.AudioIn(0x01020304, np.array([1, -2], np.int16)).encode() == bytes([4, 3, 2, 1, 1, 0, 0xFE, 0xFF])
    assert protocol.AudioOut(0x0102, 16, np.array([32767, -32768], np.int16)).encode() == \
        bytes([2, 1, 16, 0, 0, 0, 0xFF, 0x7F, 0x00, 0x80])
    out = protocol.AudioOut.decode(protocol.AudioOut(7, 99, ramp(0, 480)).encode())
    assert (out.stream, out.index, len(out.pcm)) == (7, 99, 480)
    with pytest.raises(protocol.ProtocolError):
        protocol.AudioOut(1, 0, ramp(0, 481)).encode()
    with pytest.raises(protocol.ProtocolError):
        protocol.AudioIn.decode(b"\0\0\0\0\1")
    assert protocol.audio_ctrl(protocol.AUDIO_VOLUME, 300) == bytes([4, 255])
    assert protocol.sound("wake") == b"\x03" and protocol.sound(6) == b"\x06"
    assert [i for i, _ in protocol.SOUNDS.values()] == [1, 2, 3, 4, 5, 6]
    # the firmware test checks the same durations (firmware/test/test_audio)
    assert [d for _, d in protocol.SOUNDS.values()] == [140, 120, 200, 200, 360, 430]


def test_to_pcm16_resamples_and_converts():
    assert len(to_pcm16(np.zeros(48000, np.int16), 48000)) == 16000
    assert len(to_pcm16(np.zeros(22050, np.int16), 22050)) == 16000
    x = to_pcm16(np.full(100, 0.5, np.float32))
    assert x.dtype == np.int16 and x[0] == 16384
    assert to_pcm16(np.array([[100, 300], [100, 300]], np.int16)).tolist() == [200, 200]
    # a 1 kHz tone survives 48 kHz -> 16 kHz; a 20 kHz tone is filtered out
    t = np.arange(48000) / 48000
    lo = to_pcm16((0.5 * np.sin(2 * np.pi * 1000 * t)).astype(np.float32), 48000)
    hi = to_pcm16((0.5 * np.sin(2 * np.pi * 20000 * t)).astype(np.float32), 48000)
    assert np.abs(lo[1000:-1000]).max() > 15000
    assert np.abs(hi[1000:-1000]).max() < 1500


# ---- Microphone -------------------------------------------------------------------------------

def test_receiver_dispatches_audio_in(rig):
    rx, robot, dev, sink = rig
    robot.mic(640, ramp(0, 320), t_us=123)
    robot.send(protocol.AUDIO_IN, b"\0\0\0\0\1")          # odd length: counted as bad
    deadline = time.monotonic() + 2
    while not sink.audio and time.monotonic() < deadline:
        time.sleep(0.005)
    t_us, seq, pcm = sink.audio[0]
    assert (t_us, seq, pcm.dtype, len(pcm)) == (123, 640, np.int16, 320)
    time.sleep(0.05)
    assert dev.stats.bad == 1


def test_mic_start_keepalive_and_stop(rig):
    rx, robot, dev, _ = rig
    mic = RobotMicSource(rx, dev, keepalive_s=0.05, gain_db=18)
    assert isinstance(mic, AudioSource)
    starts = robot.wait_for(protocol.AUDIO_CTRL, count=4)
    payloads = [ctrl(p) for _, _, p in starts]
    assert payloads[0] == (protocol.AUDIO_MIC_GAIN, 18)
    assert payloads[1:4] == [(protocol.AUDIO_MIC_START, 0)] * 3        # repeated while listening
    mic.close()
    time.sleep(0.1)
    assert ctrl(robot.of_type(protocol.AUDIO_CTRL)[-1][2]) == (protocol.AUDIO_MIC_STOP, 0)
    n = len(robot.of_type(protocol.AUDIO_CTRL))
    time.sleep(0.15)
    assert len(robot.of_type(protocol.AUDIO_CTRL)) == n                # keepalive stopped
    assert list(mic.frames()) == []


def test_mic_frames_gaps_duplicates_and_restart(rig):
    rx, robot, dev, _ = rig
    mic = RobotMicSource(rx, dev)
    frames = []
    reader = threading.Thread(target=lambda: frames.extend(mic.frames()), daemon=True)
    reader.start()
    def send(index, pcm, t_us=None):                # robot clock: 62.5 us per sample
        robot.mic(index, pcm, t_us=int(1_000_000 + index * 62.5) if t_us is None else t_us)

    send(0, ramp(0, 320))
    send(320, ramp(320, 320))
    send(320, ramp(320, 320))                       # duplicate: dropped
    send(960, ramp(960, 320))                       # 640..959 lost: 320 samples of silence
    send(1280, ramp(1280, 160))                     # half blocks are re-framed
    send(1440, ramp(1440, 160))
    send(640, ramp(640, 320))                       # the lost one, too late: dropped
    send(1440 + 80, ramp(1520, 240))                # overlaps by 80: trimmed
    send(0, ramp(50000, 480), t_us=5_000_000)       # the robot restarted its microphone
    deadline = time.monotonic() + 2
    while len(frames) < 7 and time.monotonic() < deadline:
        time.sleep(0.01)
    mic.close()
    reader.join(1)
    assert all(len(f) == FRAME_SAMPLES and f.dtype == np.int16 for f in frames)
    audio = np.concatenate(frames)
    expected = np.concatenate([ramp(0, 640), np.zeros(320, np.int16), ramp(960, 800), ramp(50000, 480)])
    assert len(audio) == len(expected) == 7 * FRAME_SAMPLES
    assert np.array_equal(audio, expected)
    assert mic.stats.lost_samples == 320
    assert mic.stats.late_samples == 320 + 320 + 80
    assert mic.stats.resyncs == 1


def test_mic_ignores_other_devices(rig):
    rx, robot, dev, _ = rig
    other = FakeRobot(rx.sock.getsockname()[1])
    try:
        mic = RobotMicSource(rx, dev)
        time.sleep(0.05)
        other.mic(0, ramp(0, 320))
        robot.mic(0, ramp(7, 320))
        f = next(mic.frames())
        assert np.array_equal(f, ramp(7, 320))
        mic.close()
    finally:
        other.close()


# ---- Speaker ----------------------------------------------------------------------------------

def test_speaker_chunks_and_paces_at_real_time(rig):
    rx, robot, dev, _ = rig
    spk = RobotSpeakerSink(rx, dev, lead_s=0.15)
    assert isinstance(spk, AudioSink)
    pcm = ramp(0, SAMPLE_RATE)                      # 1 s
    t0 = time.monotonic()
    spk.play(pcm)
    assert spk.busy
    spk.wait()
    elapsed = time.monotonic() - t0
    out = robot.of_type(protocol.AUDIO_OUT)
    msgs = [protocol.AudioOut.decode(p) for _, _, p in out]
    assert len(msgs) == SAMPLE_RATE // FRAME_SAMPLES
    assert all(len(m.pcm) == FRAME_SAMPLES for m in msgs)
    assert len({m.stream for m in msgs}) == 1
    assert [m.index for m in msgs] == list(range(0, SAMPLE_RATE, FRAME_SAMPLES))
    assert np.array_equal(np.concatenate([m.pcm for m in msgs]), pcm)
    # the first 150 ms go out at once, the rest at real time
    times = np.array([ts for ts, _, _ in out]) - out[0][0]
    burst = int(0.15 * SAMPLE_RATE / FRAME_SAMPLES)
    assert times[burst - 1] < 0.08
    assert 0.75 < times[-1] < 1.0
    assert 1.0 <= elapsed < 1.4                     # wait() covers the robot's playback
    assert not spk.busy
    spk.close()


def test_speaker_stop_and_new_stream(rig):
    rx, robot, dev, _ = rig
    spk = RobotSpeakerSink(rx, dev)
    spk.play(ramp(0, SAMPLE_RATE * 2), rate=SAMPLE_RATE)
    time.sleep(0.2)
    spk.stop()
    assert not spk.busy
    time.sleep(0.1)
    first = [protocol.AudioOut.decode(p) for _, _, p in robot.of_type(protocol.AUDIO_OUT)]
    assert 0 < len(first) < 40                      # stopped well before the 2 s were sent
    stops = [ctrl(p) for _, _, p in robot.of_type(protocol.AUDIO_CTRL)]
    assert stops.count((protocol.AUDIO_PLAY_STOP, 0)) == 2
    spk.play(np.zeros(640, np.int16))
    spk.wait()
    after = [protocol.AudioOut.decode(p) for _, _, p in robot.of_type(protocol.AUDIO_OUT)][len(first):]
    assert after and after[0].stream != first[0].stream and after[0].index == 0
    spk.close()


def test_speaker_consecutive_plays_continue_one_stream(rig):
    rx, robot, dev, _ = rig
    spk = RobotSpeakerSink(rx, dev)
    spk.play(ramp(0, 800))                          # 50 ms, not a multiple of the chunk
    spk.play(ramp(800, 800), rate=SAMPLE_RATE)
    spk.wait()
    msgs = [protocol.AudioOut.decode(p) for _, _, p in robot.of_type(protocol.AUDIO_OUT)]
    assert len({m.stream for m in msgs}) == 1
    assert np.array_equal(np.concatenate([m.pcm for m in msgs]), ramp(0, 1600))
    assert all(1 <= len(m.pcm) <= protocol.AUDIO_OUT_MAX_SAMPLES for m in msgs)
    spk.close()


def test_speaker_sound_and_volume(rig):
    rx, robot, dev, _ = rig
    spk = RobotSpeakerSink(rx, dev, volume=150)
    spk.play_sound("wake")
    assert spk.busy                                 # for the earcon's 200 ms
    spk.wait()
    assert robot.wait_for(protocol.SOUND)[0][2] == b"\x03"
    assert ctrl(robot.of_type(protocol.AUDIO_CTRL)[0][2]) == (protocol.AUDIO_VOLUME, 100)
    spk.set_volume(30)
    time.sleep(0.05)
    assert ctrl(robot.of_type(protocol.AUDIO_CTRL)[-1][2]) == (protocol.AUDIO_VOLUME, 30)
    with pytest.raises(KeyError):
        spk.play_sound("nope")
    spk.close()

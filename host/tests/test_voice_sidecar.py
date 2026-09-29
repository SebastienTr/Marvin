"""The voice sidecar (marvin_host.sidecar.voice) against a fake core, over real gRPC on localhost:
test mode (scripted microphone, fake Whisper and voice), the robot's audio relayed through the
core, commands, proactive speech, interruptions, health, options, the token, and the process.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import os
import queue
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

import numpy as np
import pytest

grpc = pytest.importorskip("grpc")
pytest.importorskip("grpc_health")

from grpc_health.v1 import health_pb2, health_pb2_grpc  # noqa: E402

from marvin_host import protocol  # noqa: E402
from marvin_host.audio import FRAME_SAMPLES, SAMPLE_RATE  # noqa: E402
from marvin_host.sidecar.voice import fake  # noqa: E402
from marvin_host.sidecar.voice.contract import voice_pb2 as pb  # noqa: E402
from marvin_host.sidecar.voice.contract import voice_pb2_grpc as pb_grpc  # noqa: E402
from marvin_host.sidecar.voice.relay import RobotRelay  # noqa: E402
from marvin_host.sidecar.voice.server import serve  # noqa: E402
from marvin_host.sidecar.voice.session import EngineFactory, settings_dict, voice_config  # noqa: E402
from marvin_host.voice import persona  # noqa: E402

HOST = Path(__file__).resolve().parents[1]
ROBOT = "marvin-a1b2c3"


# ---------------------------------------------------------------- a fake core

def settings(**kw) -> pb.VoiceSettings:
    """The Python defaults (proto3 would make every bool false)."""
    values = dict(stt="auto", tts="auto", wake=True, follow_up_s=0.0, speculative_stt=True, chime=True)
    values.update(kw)
    return pb.VoiceSettings(**values)


def configure(route=pb.AUDIO_ROUTE_LOCAL, **kw) -> dict:
    return {"configure": pb.Configure(contract_version=1, settings=settings(**kw), route=route)}


class FakeCore:
    """The Java core's side of Voice.Session: sends CoreToVoice, records every VoiceToCore."""

    def __init__(self, port: int, token: str = ""):
        self.channel = grpc.insecure_channel(f"127.0.0.1:{port}")
        self.stub = pb_grpc.VoiceStub(self.channel)
        self._out: queue.Queue = queue.Queue()
        self.got: list[tuple[float, pb.VoiceToCore]] = []
        self._cond = threading.Condition()
        self.ended = threading.Event()
        md = [("authorization", f"Bearer {token}")] if token else None
        self.call = self.stub.Session(iter(self._out.get, None), metadata=md)
        self.error: grpc.RpcError | None = None
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            for m in self.call:
                with self._cond:
                    self.got.append((time.monotonic(), m))
                    self._cond.notify_all()
        except grpc.RpcError as e:
            self.error = e
        finally:
            self.ended.set()
            with self._cond:
                self._cond.notify_all()

    def send(self, **kw) -> None:
        self._out.put(pb.CoreToVoice(**kw))

    def of(self, kind: str) -> list:
        with self._cond:
            return [getattr(m, kind) for _, m in self.got if m.WhichOneof("m") == kind]

    def timed(self, kind: str) -> list[tuple[float, object]]:
        with self._cond:
            return [(t, getattr(m, kind)) for t, m in self.got if m.WhichOneof("m") == kind]

    def wait(self, kind: str, pred=lambda m: True, timeout: float = 15.0):
        """The first message of `kind` matching `pred` (already received or to come)."""
        end = time.monotonic() + timeout
        with self._cond:
            while True:
                for _, m in self.got:
                    if m.WhichOneof("m") == kind and pred(getattr(m, kind)):
                        return getattr(m, kind)
                left = end - time.monotonic()
                if left <= 0:
                    kinds = [m.WhichOneof("m") for _, m in self.got]
                    raise AssertionError(f"no matching {kind} in {len(kinds)} messages: {sorted(set(kinds))}; "
                                         f"statuses {[(s.state, s.error) for s in self.of('status')]}")
                self._cond.wait(min(left, 0.1))

    def ready(self, timeout: float = 15.0) -> pb.Status:
        return self.wait("status", lambda s: s.state == pb.Status.IDLE, timeout)

    def reply(self, reply_id: int, uid: int, *pieces: str, language: str = "fr", error: str = "") -> None:
        self.send(reply_start=pb.ReplyStart(reply_id=reply_id, language=language, utterance_uid=uid))
        for p in pieces:
            self.send(text=pb.TextPiece(reply_id=reply_id, text=p))
        self.send(reply_end=pb.ReplyEnd(reply_id=reply_id, error=error))

    def close(self) -> None:
        self._out.put(None)
        self.call.cancel()
        self.channel.close()


@pytest.fixture
def sidecar():
    """sidecar(script lines, speed=...) -> (port, service); servers are stopped at the end."""
    servers = []

    def start(lines=(), speed: float = 2.0, token: str = "", reply_timeout_s: float = 30.0):
        factory = EngineFactory(fake.Script(list(lines)), speed=speed, reply_timeout_s=reply_timeout_s)
        server, port, service = serve(factory, port=0, token=token)
        servers.append((server, service))
        return port, service

    yield start
    for server, service in servers:
        if service.session is not None:
            service.session.close()
        server.stop(grace=0).wait(2)


@pytest.fixture
def cores():
    made = []

    def connect(port, token=""):
        c = FakeCore(port, token)
        made.append(c)
        return c

    yield connect
    for c in made:
        c.close()


# ---------------------------------------------------------------- the contract and the pieces

def test_contract_is_generated_from_the_proto():
    pytest.importorskip("grpc_tools")
    r = subprocess.run([sys.executable, str(HOST / "scripts" / "gen_voice_contract.py"), "--check"],
                       capture_output=True, text=True)
    assert r.returncode == 0, r.stderr


def test_settings_from_the_core():
    d = settings_dict(settings(language="en", end_silence_ms=0, echo_tail_s=0.5, stt_model=""))
    assert d["language"] == "en" and d["echo_tail_s"] == 0.5
    assert "end_silence_ms" not in d and "stt_model" not in d and d["follow_up_s"] == 0.0
    c = voice_config({**d, "end_silence_ms": 700, "chime": False})
    assert c.language == "en" and c.default_language == "en" and c.segmenter.end_silence_s == 0.7
    assert c.wake and not c.chime and c.echo_tail_s == 0.5


def test_fake_script_and_whisper():
    script = fake.Script.parse(["0.5:Marvin, quelle heure est-il ?", "3:What is the weather like in Nice?"])
    stt = fake.ScriptedWhisper(script)
    assert stt.transcribe(fake.speech(0, 1.2)).text == "Marvin, quelle heure est-il ?"
    tr = stt.transcribe(fake.speech(1, 1.5))
    assert tr.text == "What is the weather like in Nice?" and tr.language == "en" and tr.confident
    from marvin_host.voice.tts import FakeTTS
    own, rate = FakeTTS().synthesize("Il est midi.")
    assert stt.transcribe(own).text == ""                   # its own voice means nothing
    assert stt.transcribe(np.zeros(16000, np.int16)).text == ""
    assert len(script.audio()) > 4.5 * SAMPLE_RATE
    with pytest.raises(ValueError):
        fake.Script.parse(["soon:hello"])


def test_relay_translates_what_robot_audio_sends():
    sent = []
    relay = RobotRelay(sent.append)
    assert relay.audio_device() is None
    assert relay.link(ROBOT, True, True) and not relay.link(ROBOT, True, True)
    dev = relay.audio_device()
    relay.send(dev, protocol.AUDIO_CTRL, protocol.audio_ctrl(protocol.AUDIO_MIC_START))
    relay.send(dev, protocol.AUDIO_OUT, protocol.AudioOut(9, 640, np.arange(480, dtype=np.int16)).encode())
    relay.send(dev, protocol.SOUND, protocol.sound("wake"))
    ctrl, spk, snd = sent[0].ctrl, sent[1].robot_speaker, sent[2].sound
    assert (ctrl.device, ctrl.command, ctrl.argument) == (ROBOT, protocol.AUDIO_MIC_START, 0)
    assert (spk.device, spk.stream, spk.sample_index) == (ROBOT, 9, 640)
    assert np.array_equal(np.frombuffer(spk.pcm, "<i2"), np.arange(480))
    assert snd.id == protocol.SOUNDS["wake"][0]
    heard = []
    relay.add_audio_listener(lambda d, t, i, pcm: heard.append((d.name, t, i, len(pcm))))
    relay.on_frame(pb.AudioFrame(device=ROBOT, sample_index=320, robot_time_us=20000, pcm=bytes(640)))
    relay.on_frame(pb.AudioFrame(device="stranger", sample_index=0, pcm=bytes(640)))
    assert heard == [(ROBOT, 20000, 320, 320)]
    assert relay.link(ROBOT, False, False) and relay.audio_device() is None


# ---------------------------------------------------------------- over gRPC

def test_health_options_and_transcribe(sidecar):
    port, _ = sidecar([(0.0, "Marvin, bonjour")])
    ch = grpc.insecure_channel(f"127.0.0.1:{port}")
    hs = health_pb2_grpc.HealthStub(ch)
    for name in ("", "marvin.voice.v1.Voice"):
        assert hs.Check(health_pb2.HealthCheckRequest(service=name), timeout=5).status == \
            health_pb2.HealthCheckResponse.SERVING
    stub = pb_grpc.VoiceStub(ch)
    opts = stub.Options(pb.OptionsRequest(), timeout=10)
    assert [b.name for b in opts.stt][0] == "auto" and "espeak" in [b.name for b in opts.tts]
    assert "turbo" in opts.stt_models and list(opts.languages) == ["fr", "en"]
    assert all(b.installed or b.why for b in [*opts.stt, *opts.tts])
    clip = pb.AudioClip(pcm=np.asarray(fake.speech(0, 1.0), "<i2").tobytes())
    tr = stub.Transcribe(clip, timeout=10)
    assert tr.text == "Marvin, bonjour" and tr.language == "fr"
    with pytest.raises(grpc.RpcError) as e:
        stub.Transcribe(pb.AudioClip(pcm=b"\x00"), timeout=5)
    assert e.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    ch.close()


def test_a_heard_question_goes_to_the_core_and_its_answer_is_spoken(sidecar, cores):
    port, _ = sidecar([(0.8, "Marvin, quelle heure est-il ?")])
    core = cores(port)
    assert core.wait("status").state == pb.Status.STARTING      # the stream is up before Configure
    core.send(**configure())
    ready = core.ready()
    assert ready.stt == "fake" and ready.tts == "fake" and not ready.muted
    heard = core.wait("heard")
    assert (heard.text, heard.raw, heard.language, heard.source) == \
        ("quelle heure est-il ?", "Marvin, quelle heure est-il ?", "fr", "voice")
    assert heard.uid > 0 and heard.wall_time > 1e9
    assert {"endpoint", "queue", "stt"} <= set(heard.latency)
    core.wait("status", lambda s: s.state == pb.Status.THINKING)
    core.reply(41, heard.uid, "Il est midi. Bon", " appétit, si c'est l'heure.")
    spoken = core.wait("spoken")
    assert spoken.reply_id == 41 and spoken.utterance_uid == heard.uid and not spoken.interrupted
    assert spoken.text == "Il est midi. Bon appétit, si c'est l'heure."
    assert {"first_chunk", "tts", "audio_start", "total"} <= set(spoken.latency)
    says = core.of("say")
    assert [s.text for s in says] == ["Il est midi.", "Bon appétit, si c'est l'heure."]
    assert all(s.reply_id == 41 and s.seconds > 0 and len(s.envelope) == int(s.seconds * 20) for s in says)
    core.wait("status", lambda s: s.state == pb.Status.IDLE and s is core.of("status")[-1])
    states = [s.state for s in core.of("status")]
    i = states.index(pb.Status.THINKING)
    assert states[i:i + 3] == [pb.Status.THINKING, pb.Status.SPEAKING, pb.Status.IDLE]
    utt = [u.state for u in core.of("utterance")]
    assert utt[:3] == [pb.Utterance.START, pb.Utterance.END, pb.Utterance.DONE]
    levels = core.of("level")
    assert len(levels) > 20 and max(lv.mic for lv in levels) > 0.5 and any(lv.speech for lv in levels)


def test_typed_question_filler_and_a_failed_answer(sidecar, cores):
    port, _ = sidecar()
    core = cores(port)
    core.send(**configure())
    core.ready()
    core.send(ask=pb.Ask(text="What's the weather like in Nice?"))
    heard = core.wait("heard")
    assert (heard.source, heard.language) == ("typed", "en")
    core.send(reply_start=pb.ReplyStart(reply_id=5, language="en", utterance_uid=heard.uid))
    core.send(filler=pb.Filler(reply_id=5, text="Let me check.", language="en"))
    core.send(reply_end=pb.ReplyEnd(reply_id=5, error="llm_down"))
    spoken = core.wait("spoken")
    assert spoken.text == "Let me check. " + persona.phrase("llm_down", "en")
    assert "filler_start" in spoken.latency and not spoken.interrupted
    core.send(ask=pb.Ask(text="Et demain ?", language="fr"))
    assert core.wait("heard", lambda h: h.uid != heard.uid).language == "fr"


def test_stop_and_a_new_question_interrupt(sidecar, cores):
    port, _ = sidecar()
    core = cores(port)
    core.send(**configure())
    core.ready()
    core.send(ask=pb.Ask(text="Raconte-moi une histoire."))
    first = core.wait("heard")
    core.send(ask=pb.Ask(text="Non, plutôt la météo."))       # before any answer came
    second = core.wait("heard", lambda h: h.uid != first.uid)
    gone = core.wait("interrupted", lambda m: m.utterance_uid == first.uid)
    assert gone.reason == "stop" and gone.reply_id == 0
    core.reply(1, first.uid, "Trop tard.")                  # the answer to the first question: dropped
    core.send(reply_start=pb.ReplyStart(reply_id=2, language="fr", utterance_uid=second.uid))
    core.send(text=pb.TextPiece(reply_id=2, text="Il était une fois un phare au bout du monde. " * 3))
    core.wait("say", lambda s: s.reply_id == 2)
    core.send(stop=pb.StopSpeaking())
    stopped = core.wait("interrupted", lambda m: m.reply_id == 2)
    assert stopped.reason == "stop" and stopped.utterance_uid == second.uid
    assert core.wait("spoken", lambda m: m.reply_id == 2).interrupted
    assert not [s for s in core.of("say") if s.reply_id == 1]
    core.wait("status", lambda s: s.state == pb.Status.IDLE and s is core.of("status")[-1])


def test_proactive_speech_waits_for_the_conversation(sidecar, cores):
    port, _ = sidecar()
    core = cores(port)
    core.send(**configure())
    core.ready()
    core.send(say=pb.Say(reply_id=3, text="Tu es assis depuis une heure.", language="fr"))
    assert core.wait("spoken", lambda m: m.reply_id == 3).text == "Tu es assis depuis une heure."
    core.send(reply_start=pb.ReplyStart(reply_id=4, language="fr", proactive=True))    # streamed
    core.send(text=pb.TextPiece(reply_id=4, text="Re-bonjour ! Tu étais parti "))
    core.send(text=pb.TextPiece(reply_id=4, text="longtemps."))
    core.send(reply_end=pb.ReplyEnd(reply_id=4))
    s4 = core.wait("spoken", lambda m: m.reply_id == 4)
    assert s4.text == "Re-bonjour ! Tu étais parti longtemps." and s4.utterance_uid == 0
    core.send(ask=pb.Ask(text="Quelle heure est-il ?"))
    heard = core.wait("heard")
    core.send(say=pb.Say(reply_id=6, text="Une pause ?", language="fr"))
    assert core.wait("interrupted", lambda m: m.reply_id == 6).reason == "busy"
    core.send(say=pb.Say(reply_id=7, text="Important.", language="fr", force=True))   # after the answer
    core.reply(8, heard.uid, "Midi.")
    assert core.wait("say", lambda s: s.reply_id == 7).text == "Important."
    assert [s.reply_id for s in core.of("say")][-2:] == [8, 7]


def test_mute_and_talk_now(sidecar, cores):
    port, _ = sidecar()
    core = cores(port)
    core.send(mute=pb.Mute(muted=True))                     # before the engine: remembered
    core.send(**configure())
    assert core.ready().muted
    core.send(listen_now=pb.ListenNow(on=True))             # unmutes, like the app's Talk now
    listening = core.wait("status", lambda s: s.state == pb.Status.LISTENING)
    assert not listening.muted and 5.0 < listening.listen_s <= 6.0
    core.send(listen_now=pb.ListenNow(on=False))
    last = core.wait("status", lambda s: s.state == pb.Status.IDLE and not s.HasField("listen_s")
                     and s is core.of("status")[-1])
    assert not last.muted


def test_a_question_that_goes_on_is_one_question(sidecar, cores):
    first = "Marvin, je veux aussi que tu saches que je suis développeur."
    t2 = 1.0 + fake.speech_seconds(first) + 0.9          # he goes on after a 0.9 s pause
    port, _ = sidecar([(1.0, first), (t2, "Et j'aime la voile.")])
    core = cores(port)
    core.send(**configure())
    core.ready()
    q1 = core.wait("heard")
    assert q1.text == "je veux aussi que tu saches que je suis développeur." and not q1.continues
    # the core is still thinking (no reply yet): the question is cancelled, and asked again whole
    gone = core.wait("interrupted", lambda m: m.utterance_uid == q1.uid)
    assert gone.reason == "merged"
    q2 = core.wait("heard", lambda h: h.uid != q1.uid)
    assert q2.text == "je veux aussi que tu saches que je suis développeur. Et j'aime la voile."
    assert list(q2.continues) == [q1.uid]
    core.reply(9, q1.uid, "Trop tard.")                  # an answer to the cancelled question is not said
    core.reply(10, q2.uid, "Développeur et marin, c'est noté.")
    spoken = core.wait("spoken", lambda m: m.utterance_uid == q2.uid)
    assert spoken.text == "Développeur et marin, c'est noté." and not spoken.interrupted
    assert all(s.reply_id != 9 for s in core.of("say"))


def test_the_listening_window_waits_while_someone_talks(sidecar, cores):
    port, _ = sidecar([(3.0, "Quelle heure est-il ?")])
    core = cores(port)
    core.send(**configure())
    core.ready()
    core.send(listen_now=pb.ListenNow(on=True))
    core.wait("status", lambda s: s.state == pb.Status.LISTENING and s.HasField("listen_s"))
    talking = core.wait("status", lambda s: s.state == pb.Status.LISTENING and s.hearing)
    assert not talking.HasField("listen_s")              # no countdown while they talk
    assert core.wait("heard").text == "Quelle heure est-il ?"


def test_robot_audio_through_the_core(sidecar, cores):
    port, _ = sidecar([(1000.0, "Marvin, allume la lumière.")])   # phrase 0, said by the robot below
    core = cores(port)
    core.send(**configure(route=pb.AUDIO_ROUTE_ROBOT))
    err = core.wait("status", lambda s: s.state == pb.Status.ERROR)
    assert "No robot" in err.error and err.fix
    core.send(robot_link=pb.RobotLink(device=ROBOT, connected=True, has_audio=True))
    core.ready()
    start = core.wait("ctrl", lambda c: c.command == protocol.AUDIO_MIC_START)
    assert start.device == ROBOT
    # the robot's microphone: "Marvin, ..." as phrase 0 of the fake Whisper, in 20 ms AUDIO_IN
    audio = np.concatenate([np.zeros(8000, np.int16), fake.speech(0, 1.4), np.zeros(16000, np.int16)])
    rng = np.random.default_rng(3)
    audio = np.clip(audio + rng.normal(0, 30, len(audio)), -32768, 32767).astype(np.int16)
    t_us = 5_000_000
    for i in range(len(audio) // FRAME_SAMPLES):
        pcm = audio[i * FRAME_SAMPLES:(i + 1) * FRAME_SAMPLES]
        core.send(robot_mic=pb.AudioFrame(device=ROBOT, sample_index=i * FRAME_SAMPLES,
                                          robot_time_us=t_us + i * 20_000, pcm=np.asarray(pcm, "<i2").tobytes()))
        if i % 5 == 0:
            time.sleep(0.02)
    heard = core.wait("heard")
    assert heard.text == "allume la lumière." and heard.source == "voice"
    t_reply = time.monotonic()
    core.reply(9, heard.uid, "D'accord, c'est fait.")
    spoken = core.wait("spoken")
    frames = core.timed("robot_speaker")
    assert frames, "no speaker frames for the robot"
    assert {f.device for _, f in frames} == {ROBOT} and len({f.stream for _, f in frames}) == 1
    n, idx = 0, []
    for _, f in frames:
        k = len(f.pcm) // 2
        assert 1 <= k <= protocol.AUDIO_OUT_MAX_SAMPLES
        idx.append((f.sample_index, k))
        n += k
    assert all(a + k == b for (a, k), (b, _) in zip(idx, idx[1:]))      # contiguous in one stream
    seconds = n / SAMPLE_RATE
    assert seconds == pytest.approx(len("D'accord, c'est fait.") * 0.020, abs=0.05)
    # paced: sent at real time with a 150 ms lead, not in one burst
    span = frames[-1][0] - frames[0][0]
    assert span > seconds - 0.25 and frames[0][0] >= t_reply
    assert spoken.text == "D'accord, c'est fait." and "audio_start" in spoken.latency
    core.send(robot_link=pb.RobotLink(device=ROBOT, connected=False))
    core.wait("ctrl", lambda c: c.command == protocol.AUDIO_MIC_STOP)
    core.wait("status", lambda s: s.state == pb.Status.ERROR and s is core.of("status")[-1])


def test_a_new_session_replaces_the_old_one(sidecar, cores):
    port, service = sidecar()
    a = cores(port)
    a.send(**configure())
    a.ready()
    b = cores(port)
    assert a.ended.wait(5)
    assert a.of("status")[-1].state == pb.Status.STOPPED
    b.send(**configure())
    b.ready()
    assert service.session is not None


def test_commands_while_the_voice_is_off(sidecar, cores):
    port, _ = sidecar()
    core = cores(port)
    core.send(ask=pb.Ask(text="Allô ?"))
    core.send(say=pb.Say(reply_id=8, text="Bonjour."))
    assert core.wait("ignored").reason == "the voice is not running"
    assert core.wait("interrupted").reason == "off"


def test_no_answer_from_the_core_is_said(sidecar, cores):
    port, _ = sidecar(reply_timeout_s=0.5)
    core = cores(port)
    core.send(**configure())
    core.ready()
    core.send(ask=pb.Ask(text="Tu es là ?"))
    spoken = core.wait("spoken")
    assert spoken.text == persona.phrase("error", "fr") and spoken.reply_id == 0


def test_token(sidecar, cores):
    port, _ = sidecar(token="s3cret")
    stub = pb_grpc.VoiceStub(grpc.insecure_channel(f"127.0.0.1:{port}"))
    with pytest.raises(grpc.RpcError) as e:
        stub.Options(pb.OptionsRequest(), timeout=5)
    assert e.value.code() == grpc.StatusCode.UNAUTHENTICATED
    assert stub.Options(pb.OptionsRequest(), timeout=5, metadata=[("authorization", "Bearer s3cret")]).languages
    bad = cores(port, token="wrong")
    assert bad.ended.wait(5) and bad.error.code() == grpc.StatusCode.UNAUTHENTICATED
    good = cores(port, token="s3cret")
    assert good.wait("status").state == pb.Status.STARTING


def test_the_process_says_ready_and_stops_on_sigterm():
    env = {**os.environ, "PYTHONPATH": str(HOST)}
    p = subprocess.Popen([sys.executable, "-m", "marvin_host.sidecar.voice", "--port", "0", "--fake",
                          "--say", "1:Marvin, bonjour"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         text=True, env=env)
    try:
        line = p.stdout.readline().strip()
        assert line.startswith("READY port="), (line, p.stderr.read() if p.poll() is not None else "")
        port = int(line.split("=")[1])
        ch = grpc.insecure_channel(f"127.0.0.1:{port}")
        st = health_pb2_grpc.HealthStub(ch).Check(health_pb2.HealthCheckRequest(), timeout=5)
        assert st.status == health_pb2.HealthCheckResponse.SERVING
        ch.close()
        p.send_signal(signal.SIGTERM)
        assert p.wait(10) == 0
    finally:
        if p.poll() is None:
            p.kill()
        p.stdout.close()
        p.stderr.close()


def test_saying_marvin_while_it_thinks_is_a_barge_in(sidecar, cores):
    port, _ = sidecar([(0.5, "Marvin, raconte-moi une histoire."), (4.5, "Marvin !")])
    core = cores(port)
    core.send(**configure())
    core.ready()
    first = core.wait("heard")
    assert first.text == "raconte-moi une histoire."
    # the core is slow: "Marvin !" stops the wait and opens a listening window
    gone = core.wait("interrupted", lambda m: m.utterance_uid == first.uid)
    assert gone.reason == "barge-in"
    assert core.wait("spoken", lambda m: m.utterance_uid == first.uid).interrupted
    core.wait("status", lambda s: s.state == pb.Status.LISTENING and s.listen_s > 0)

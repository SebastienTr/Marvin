"""One live session with the core (Voice.Session): configuration, commands, and the engine's life.

The core opens the stream and sends `Configure` first, then again whenever the settings change.
`VoiceSession` builds the engine for them (in the background: loading Whisper and a voice takes
seconds) and rebuilds it when the settings, the audio route or the robot change; loaded models are
kept when their own settings did not change. Until an engine runs, `Status` says STARTING, or ERROR
with what is missing and how to fix it, and commands are answered as the voice being off.

`EngineFactory` makes the components: the computer's microphone and speakers (voice/io.py) or the
robot's, relayed by the core (relay.py and robot_audio.py); Whisper and the speech synthesis the
settings ask for; or, in test mode, a scripted microphone, a fake Whisper and a fake voice
(fake.py).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import threading
from typing import Callable

from ...voice import cli as voice_cli
from ...voice.control import VoiceUnavailable, audio_preflight
from ...voice.engine import VoiceConfig, load_tts
from .contract import voice_pb2 as pb
from .engine import SidecarVoice
from .fake import Script, ScriptedMic, ScriptedWhisper
from .relay import RobotRelay

log = logging.getLogger("marvin.sidecar.voice")

CONTRACT_VERSION = 1
LOCAL, ROBOT = pb.AUDIO_ROUTE_LOCAL, pb.AUDIO_ROUTE_ROBOT


def settings_dict(s: pb.VoiceSettings) -> dict:
    """`VoiceSettings` as voice.json keys (voice/cli.py). Proto3 cannot tell 0 from unset: empty
    strings and a 0 for echo_tail_s, listen_window_s or end_silence_ms mean "the default"
    (follow_up_s 0 means no follow-up window, as in voice.json)."""
    out = {"stt": s.stt or "auto", "tts": s.tts or "auto", "wake": s.wake, "duplex": s.duplex,
           "follow_up_s": s.follow_up_s, "speculative_stt": s.speculative_stt, "chime": s.chime}
    for key in ("stt_model", "tts_voice", "language", "default_language", "input_device", "output_device"):
        v = getattr(s, key)
        if v:
            out[key] = v
    for key in ("echo_tail_s", "listen_window_s", "end_silence_ms"):
        v = getattr(s, key)
        if v > 0:
            out[key] = v
    return out


def voice_config(settings: dict) -> VoiceConfig:
    c = voice_cli._config(None, settings=settings)
    c.chime = bool(settings.get("chime", True))
    return c


def stt_name(stt, config: VoiceConfig) -> str:
    kind = type(stt).__name__
    backend = {"MlxWhisperSTT": "mlx", "WhisperSTT": "faster-whisper", "ScriptedWhisper": "fake"}.get(kind, kind)
    model = getattr(stt, "model_name", None) or config.stt_model or ""
    return f"{backend} {model}".strip()


def tts_name(tts) -> str:
    kind = type(tts).__name__
    names = {"MacSayTTS": "say", "PiperTTS": "piper", "EspeakTTS": "espeak", "FakeTTS": "fake"}
    if kind == "FallbackTTS":
        return f"{tts_name(tts.primary)} (else {tts_name(tts.fallback)})"
    return names.get(kind, kind)


class EngineFactory:
    """Builds `SidecarVoice`s; keeps the loaded speech recognition and synthesis between builds.
    `script`: test mode (fake.py), with the scripted microphone played at `speed`."""

    def __init__(self, script: Script | None = None, speed: float = 1.0, reply_timeout_s: float = 30.0):
        self.script = script
        self.speed = speed
        self.reply_timeout_s = reply_timeout_s
        self._lock = threading.Lock()
        self.stt_lock = threading.Lock()            # one recognition at a time: live loop and Transcribe
        self._stt: tuple[tuple, object] | None = None
        self._tts: tuple[tuple, object] | None = None

    @property
    def fake(self) -> bool:
        return self.script is not None

    def stt(self, c: VoiceConfig):
        languages = (c.language,) if c.language else c.languages
        key = (c.stt, c.stt_model, languages)
        with self._lock:
            if self._stt is None or self._stt[0] != key:
                if self.fake:
                    stt = ScriptedWhisper(self.script, languages)
                else:
                    from ...voice.stt import make_stt
                    stt = make_stt(c.stt, c.stt_model, languages)
                self._stt = (key, stt)
            return self._stt[1]

    def tts(self, c: VoiceConfig):
        key = (c.tts, c.tts_voice, c.language or c.default_language)
        with self._lock:
            if self._tts is None or self._tts[0] != key:
                if self.fake:
                    from ...voice.tts import FakeTTS
                    tts = FakeTTS()
                else:
                    tts = load_tts(c)
                self._tts = (key, tts)
            return self._tts[1]

    def build(self, settings: dict, route: int, relay: RobotRelay,
              send: Callable[[pb.VoiceToCore], None]) -> SidecarVoice:
        """Raises VoiceUnavailable when something is missing."""
        c = voice_config(settings)
        if route == ROBOT:
            dev = relay.audio_device()
            if dev is None:
                raise VoiceUnavailable("No robot with a microphone and a speaker is connected",
                                       "Turn the robot on, or choose the computer's microphone.")
            if not self.fake:
                audio_preflight(settings, microphone=False)
            from ...robot_audio import RobotMicSource, RobotSpeakerSink
            source, sink = RobotMicSource(relay, dev), RobotSpeakerSink(relay, dev)
        elif self.fake:
            from ...voice.io import NullSink
            source, sink = ScriptedMic(self.script, self.speed), NullSink(realtime=True)
        else:
            audio_preflight(settings, microphone=True)
            from ...voice import io
            source = io.MicSource(voice_cli._device(settings.get("input_device")))
            sink = io.SpeakerSink(voice_cli._device(settings.get("output_device")))
        try:
            stt, tts = self.stt(c), self.tts(c)
            vad = None
            if self.fake:
                from ...voice.vad import EnergyVad
                vad = EnergyVad()
            eng = SidecarVoice(source, sink, c, stt=stt, tts=tts, vad=vad, send=send,
                               names={"stt": stt_name(stt, c), "tts": tts_name(tts)},
                               reply_timeout_s=self.reply_timeout_s)
            eng._stt_lock = self.stt_lock
            return eng
        except BaseException:
            source.close()
            if hasattr(sink, "close"):
                sink.close()
            raise


class VoiceSession:
    """See the module docstring. `send(VoiceToCore)` delivers to the core; `send(None)` ends the
    stream (the session was closed, e.g. replaced by a newer one)."""

    def __init__(self, factory: EngineFactory, send: Callable[[pb.VoiceToCore | None], None]):
        self.factory = factory
        self._send = send
        self.relay = RobotRelay(self._out)
        self._lock = threading.RLock()
        self.engine: SidecarVoice | None = None
        self.state = pb.Status.STARTING
        self.error = self.fix = ""
        self.muted = False
        self._settings: dict | None = None
        self._route = LOCAL
        self._built_for: tuple | None = None
        self._wanted = threading.Event()
        self._closed = False
        self._worker = threading.Thread(target=self._build_loop, name="marvin-sidecar-build", daemon=True)
        self._worker.start()
        self._status()                              # the stream is up: waiting for Configure

    # ------------------------------------------------------------ messages from the core

    def handle(self, m: pb.CoreToVoice) -> None:
        kind = m.WhichOneof("m")
        if kind == "robot_mic":
            self.relay.on_frame(m.robot_mic)
            return
        if kind == "configure":
            self._configure(m.configure)
        elif kind == "robot_link":
            r = m.robot_link
            if self.relay.link(r.device, r.connected, r.has_audio) and self._route == ROBOT:
                self._rebuild()
        elif kind == "mute":
            self.muted = m.mute.muted
            eng = self.engine
            if eng is not None:
                eng.mute(self.muted)
            else:
                self._status()
        elif kind in ("reply_start", "text", "filler", "reply_end", "say", "ask", "listen_now", "stop"):
            self._command(kind, getattr(m, kind))
        elif kind is None:
            log.warning("empty message from the core")

    def _command(self, kind: str, m) -> None:
        eng = self.engine
        if eng is None or eng.closed:
            if kind == "ask":
                self._out(pb.VoiceToCore(ignored=pb.Ignored(text=m.text, reason="the voice is not running")))
            elif kind in ("reply_start", "say"):
                self._out(pb.VoiceToCore(interrupted=pb.Interrupted(reply_id=m.reply_id, reason="off")))
            else:
                self._status()
            return
        if kind == "reply_start":
            eng.reply_start(m)
        elif kind == "text":
            eng.text(m)
        elif kind == "filler":
            eng.filler(m)
        elif kind == "reply_end":
            eng.reply_end(m)
        elif kind == "say":
            eng.say_now(m)
        elif kind == "ask":
            text = m.text.strip()
            if text:
                eng.ask(text[:500], m.language or None)
        elif kind == "listen_now":
            if m.on:
                eng.listen_now()
            else:
                eng.stop_listening()
            self._out(eng.status_message())        # listen_s changed, maybe not the state
        elif kind == "stop":
            eng.stop_speaking()

    def _configure(self, c: pb.Configure) -> None:
        if c.contract_version not in (0, CONTRACT_VERSION):
            log.warning("the core speaks contract version %d, this sidecar %d", c.contract_version, CONTRACT_VERSION)
        with self._lock:
            self._settings = settings_dict(c.settings)
            self._route = c.route if c.route != pb.AUDIO_ROUTE_UNSPECIFIED else LOCAL
        self._rebuild()

    # ------------------------------------------------------------ the engine's life

    def _rebuild(self) -> None:
        self._wanted.set()

    def _target(self) -> tuple | None:
        with self._lock:
            if self._settings is None:
                return None
            dev = self.relay.audio_device() if self._route == ROBOT else None
            return (tuple(sorted(self._settings.items())), self._route, dev.name if dev else None)

    def _build_loop(self) -> None:
        while True:
            self._wanted.wait()
            self._wanted.clear()
            if self._closed:
                return
            target = self._target()
            if target is None or target == self._built_for:
                continue
            self._stop_engine()
            self._set_state(pb.Status.STARTING)
            settings, route = dict(target[0]), target[1]
            try:
                eng = self.factory.build(settings, route, self.relay, self._out)
            except VoiceUnavailable as e:
                self._built_for = target
                self._set_state(pb.Status.ERROR, str(e), e.fix)
                continue
            except Exception as e:                  # noqa: BLE001 - reported to the core
                log.exception("the voice failed to start")
                self._built_for = target
                self._set_state(pb.Status.ERROR, f"The voice failed to start: {e}", "See the voice sidecar's log.")
                continue
            if self._closed:
                eng.close()
                return
            if self.muted:
                eng.mute(True)
            with self._lock:
                self.engine = eng
                self._built_for = target
                self.error = self.fix = ""
            eng.start()
            log.info("voice ready (%s, %s; %s)", eng.names.get("stt"), eng.names.get("tts"),
                     "robot" if route == ROBOT else "computer")
            self._out(eng.status_message())

    def _stop_engine(self) -> None:
        with self._lock:
            eng, self.engine = self.engine, None
        if eng is not None:
            try:
                eng.close()
            except Exception:                       # noqa: BLE001
                log.exception("closing the voice failed")

    def _set_state(self, state: int, error: str = "", fix: str = "") -> None:
        with self._lock:
            self.state, self.error, self.fix = state, error, fix
        if error:
            log.warning("voice: %s. %s", error, fix)
        self._status()

    def _status(self) -> None:
        eng = self.engine
        if eng is not None and not eng.closed:
            self._out(eng.status_message())
        else:
            self._out(pb.VoiceToCore(status=pb.Status(state=self.state, muted=self.muted, error=self.error,
                                                      fix=self.fix)))

    def _out(self, m: pb.VoiceToCore) -> None:
        if not self._closed:
            self._send(m)

    def close(self) -> None:
        """Stops the engine and ends the stream."""
        if self._closed:
            return
        self._stop_engine()
        self._closed = True
        self._wanted.set()
        self._send(pb.VoiceToCore(status=pb.Status(state=pb.Status.STOPPED, muted=self.muted)))
        self._send(None)

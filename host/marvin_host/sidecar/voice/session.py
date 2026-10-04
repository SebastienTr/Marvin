"""One live session with the core (Voice.Session): configuration, commands, and the engine's life.

The core opens the stream and sends `Configure` first, then again whenever the settings or the
audio device change. Contract version 2: the core sends every value (it is the single source of
the voice's settings and defaults, host-java `VoiceSettings`) and the device to use with its
profile (device.py): this side never fills in a default nor decides the route.

`VoiceSession` builds the engine for them in the background (loading Whisper and a voice takes
seconds). A new device alone (a robot linked or left) is swapped under the running engine; other
changes rebuild it, keeping the loaded models whose own settings did not change. Until an engine
runs, `Status` says STARTING, or ERROR with what is missing and how to fix it, and commands are
answered as the voice being off.

`EngineFactory` makes the components: the computer's microphone and speakers (voice/io.py) or the
robot's, relayed by the core (relay.py and robot_audio.py); Whisper and the speech synthesis the
settings ask for; or, in test mode, a scripted microphone, a fake Whisper and a fake voice
(fake.py).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import threading
from dataclasses import astuple
from typing import Callable

from ...voice.backends import VoiceUnavailable, audio_preflight, device_index
from ...voice.device import COMPUTER, ROBOT, AudioDevice, DeviceProfile
from ...voice.engine import VoiceConfig, load_tts
from ...voice.vad import SegmenterConfig
from .contract import voice_pb2 as pb
from .engine import SidecarVoice
from .fake import Script, ScriptedMic, ScriptedWhisper
from .relay import RobotRelay

log = logging.getLogger("marvin.sidecar.voice")

CONTRACT_VERSION = 2


def voice_config(s: pb.VoiceSettings) -> VoiceConfig:
    """The voice's settings as the core sends them (version 2: every value is there; the ears'
    fields, added later, fall back to the same defaults when an older core leaves them unset)."""
    language = s.language or None
    d = VoiceConfig()
    return VoiceConfig(
        language=language, default_language=language or s.default_language or "fr",
        stt=s.stt, stt_model=s.stt_model or None, tts=s.tts, tts_voice=s.tts_voice or None,
        wake=s.wake, listen_window_s=s.listen_window_s, follow_up_s=s.follow_up_s,
        speculative_stt=s.speculative_stt, speculate_after_s=s.speculate_after_s,
        continue_grace_s=s.continue_grace_s, continue_join_s=s.continue_join_s,
        segmenter=SegmenterConfig(end_silence_s=s.end_silence_ms / 1000,
                                  end_silence_long_s=s.end_silence_long_ms / 1000),
        vad=s.vad or d.vad, turn=s.turn or d.turn,
        turn_check_s=s.turn_check_ms / 1000 if s.turn_check_ms > 0 else d.turn_check_s,
        turn_max_s=s.turn_max_ms / 1000 if s.turn_max_ms > 0 else d.turn_max_s,
        vocabulary=tuple(s.vocabulary),
        voiceprints=tuple((v.name, tuple(round(float(x), 6) for x in v.embedding), v.model)
                          for v in s.voiceprints if v.name and len(v.embedding)),
        speaker_threshold=s.speaker_threshold if s.speaker_threshold > 0 else d.speaker_threshold,
        known_voices_only=s.known_voices_only)


def device_profile(d: pb.AudioDeviceProfile) -> DeviceProfile:
    return DeviceProfile(kind=ROBOT if d.kind == ROBOT else COMPUTER, robot=d.robot, duplex=d.duplex,
                         echo_tail_s=d.echo_tail_s, output_latency_s=d.output_latency_s,
                         vad_min_dbfs=d.vad_min_dbfs, speech_min_dbfs=d.speech_min_dbfs,
                         vad_aggressiveness=d.vad_aggressiveness, vad_threshold=d.vad_threshold or 0.5,
                         has_earcons=d.has_earcons, has_aec=d.has_aec,
                         input_device=d.input_device, output_device=d.output_device)


def stt_name(stt, config: VoiceConfig) -> str:
    stt = getattr(stt, "inner", stt)                   # LexiconSTT: the engine under the vocabulary
    kind = type(stt).__name__
    backend = {"MlxWhisperSTT": "mlx", "WhisperSTT": "faster-whisper", "ScriptedWhisper": "fake",
               "ParakeetSTT": f"parakeet ({getattr(stt, 'backend', '')})"}.get(kind, kind)
    model = getattr(stt, "model_name", None) or config.stt_model or ""
    return f"{backend} {model}".strip()


def ears_name(engine) -> str:
    """What the ears run with, for the log: the VAD, the end of turn, speaker recognition."""
    vad = type(engine.segmenter.vad).__name__.replace("Vad", "").lower() or "vad"
    turn = getattr(engine.turn, "name", None) or "silence"
    spk = engine.speaker
    who = "no speaker model" if spk is None else f"{len(spk.voiceprints)} voiceprint(s)"
    return f"{vad} VAD, {turn} end of turn, {who}"


def tts_name(tts) -> str:
    kind = type(tts).__name__
    names = {"MacSayTTS": "say", "PiperTTS": "piper", "EspeakTTS": "espeak", "FakeTTS": "fake"}
    if kind == "FallbackTTS":
        return f"{tts_name(tts.primary)} (else {tts_name(tts.fallback)})"
    return getattr(tts, "name", None) or names.get(kind, kind)


class EngineFactory:
    """Builds `SidecarVoice`s and their devices; keeps the loaded speech recognition and synthesis
    between builds. `script`: test mode (fake.py), with the scripted microphone played at `speed`."""

    def __init__(self, script: Script | None = None, speed: float = 1.0, reply_timeout_s: float = 30.0):
        self.script = script
        self.speed = speed
        self.reply_timeout_s = reply_timeout_s
        self._lock = threading.Lock()
        self.stt_lock = threading.Lock()            # one recognition at a time: live loop and Transcribe
        self._stt: tuple[tuple, object] | None = None
        self._tts: tuple[tuple, object] | None = None
        self._turn: tuple[str, object] | None = None
        self._encoder: tuple[bool] | None = None        # (the speaker encoder or None,) once tried

    @property
    def fake(self) -> bool:
        return self.script is not None

    def stt(self, c: VoiceConfig):
        """The speech recognition (kept while its own settings do not change), with the vocabulary
        (applied without reloading the model)."""
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
            stt = self._stt[1]
        if self.fake:
            return stt
        from ...voice.stt import with_vocabulary
        return with_vocabulary(stt, c.vocabulary)

    def turn(self, c: VoiceConfig):
        """Smart Turn (kept), or None for the silence timers (and in test mode)."""
        if self.fake:
            return None
        with self._lock:
            if self._turn is None or self._turn[0] != c.turn:
                from ...voice.turn import make_turn
                self._turn = (c.turn, make_turn(c.turn))
            return self._turn[1]

    def encoder(self):
        """The speaker model (loaded once), or None when it is not here (and in test mode)."""
        if self.fake:
            return None
        with self._lock:
            if self._encoder is None or self._encoder[0] is None:
                from ...voice.speaker import make_encoder
                self._encoder = (make_encoder(),)
            return self._encoder[0]

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

    def device(self, profile: DeviceProfile, relay: RobotRelay) -> AudioDevice:
        """The microphone and speaker of `profile`. Raises VoiceUnavailable when they cannot open."""
        if profile.kind == ROBOT:
            if not profile.robot:
                raise VoiceUnavailable("No robot with a microphone and a speaker is connected",
                                       "Turn the robot on, or choose the computer's microphone.")
            if not self.fake:
                audio_preflight(microphone=False)
            from ...robot_audio import RobotMicSource, RobotSpeakerSink
            dev = relay.device(profile.robot)
            return AudioDevice(RobotMicSource(relay, dev), RobotSpeakerSink(relay, dev), profile)
        if self.fake:
            from ...voice.io import NullSink
            return AudioDevice(ScriptedMic(self.script, self.speed), NullSink(realtime=True), profile)
        audio_preflight(profile.input_device, microphone=True)
        from ...voice import io
        source = io.MicSource(device_index(profile.input_device))
        try:
            sink = io.SpeakerSink(device_index(profile.output_device))
        except BaseException:
            source.close()
            raise
        return AudioDevice(source, sink, profile)

    def build(self, config: VoiceConfig, profile: DeviceProfile, relay: RobotRelay,
              send: Callable[[pb.VoiceToCore], None]) -> SidecarVoice:
        """Raises VoiceUnavailable when something is missing."""
        device = self.device(profile, relay)
        try:
            stt, tts = self.stt(config), self.tts(config)
            vad = None
            if self.fake:
                from ...voice.vad import EnergyVad
                vad = EnergyVad()
            return SidecarVoice(device, config, stt=stt, tts=tts, vad=vad, turn=self.turn(config),
                                speaker=self.encoder(), stt_lock=self.stt_lock, send=send,
                                names={"stt": stt_name(stt, config), "tts": tts_name(tts)},
                                reply_timeout_s=self.reply_timeout_s)
        except BaseException:
            device.close()
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
        self.config: VoiceConfig | None = None
        self.profile: DeviceProfile | None = None
        self._built_for: tuple | None = None        # (config, profile) of the engine, or of the failure
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
            self.relay.link(r.device, r.connected, r.has_audio)     # what is known; the core chooses the device
        elif kind == "mute":
            self.muted = m.mute.muted
            eng = self.engine
            if eng is not None:
                eng.mute(self.muted)
            else:
                self._status()
        elif kind in ("reply_start", "text", "filler", "reply_end", "say", "ask", "listen_now", "stop", "earcon",
                      "enroll"):
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
            elif kind == "enroll" and m.action != "cancel":
                self._out(pb.VoiceToCore(enroll=pb.EnrollProgress(state="failed",
                                                                  message="The voice is not running: turn it on first.")))
            elif kind != "earcon":
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
        elif kind == "earcon":
            eng.earcon(m.sound)
        elif kind == "enroll":
            if m.action == "cancel":
                eng.enroll_cancel()
            else:
                from ...voice.enroll import NOISE_S
                eng.enroll(m.name, list(m.sentences), m.noise_s if m.noise_s > 0 else NOISE_S)

    def _configure(self, c: pb.Configure) -> None:
        if c.contract_version != CONTRACT_VERSION:
            log.error("the core speaks contract version %d, this sidecar %d", c.contract_version, CONTRACT_VERSION)
            self._set_state(pb.Status.ERROR, f"The core speaks voice contract version {c.contract_version}, "
                                             f"this voice sidecar version {CONTRACT_VERSION}",
                            "./marvin restart (the host and its voice must come from the same checkout)")
            return
        with self._lock:
            self.config = voice_config(c.settings)
            self.profile = device_profile(c.device)
        self._wanted.set()

    # ------------------------------------------------------------ the engine's life

    def _build_loop(self) -> None:
        while True:
            self._wanted.wait()
            self._wanted.clear()
            if self._closed:
                return
            with self._lock:
                config, profile = self.config, self.profile
            if config is None or profile is None:
                continue
            target = (astuple(config), profile)
            built = self._built_for
            if target == built:
                continue
            eng = self.engine
            if eng is not None and not eng.closed and built is not None and built[0] == target[0]:
                self._swap(eng, profile, target)       # only the device changed: models and state stay
                continue
            self._stop_engine()
            self._set_state(pb.Status.STARTING)
            try:
                eng = self.factory.build(config, profile, self.relay, self._out)
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
            log.info("voice ready (%s, %s; %s; %s)", eng.names.get("stt"), eng.names.get("tts"), ears_name(eng),
                     profile.name)
            self._out(eng.status_message())

    def _swap(self, eng: SidecarVoice, profile: DeviceProfile, target: tuple) -> None:
        try:
            device = self.factory.device(profile, self.relay)
        except VoiceUnavailable as e:
            self._built_for = target
            self._stop_engine()
            self._set_state(pb.Status.ERROR, str(e), e.fix)
            return
        eng.swap_device(device)
        with self._lock:
            self._built_for = target
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

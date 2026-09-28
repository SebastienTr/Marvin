"""Turning Marvin's voice on and off while marvin-host runs, for the app (docs/ui.md).

`VoiceController` owns the `VoiceAssistant`'s life: it checks what the voice needs (the `voice`
extra, a microphone, Ollama and the model) and says how to fix what is missing, builds the
assistant from the owner's settings (voice.json, the same file `marvin-host talk` reads), starts it
in the background (loading the models takes a few seconds), restarts it when the settings change,
and stops it. It keeps the recent conversation and passes the assistant's events on to several
listeners (the app's live stream). With a store (`attach_store`, the app's SQLite file), every
conversation entry is kept there too, and the recent ones come back after a restart.

    ctl = VoiceController(brain)                  # nothing starts yet
    ctl.add_listener(lambda kind, payload: ...)   # "voice" (state), "transcript" (one entry) and
                                                  # the live signals in LIVE_KINDS
    ctl.attach_store(store)                       # optional: keep the conversation (ui/store.py)
    ctl.start()                                   # background; ctl.snapshot()["state"] says how it goes
    ctl.ask("Quelle heure est-il ?")
    ctl.stop()

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import importlib.util
import itertools
import json
import logging
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
from datetime import datetime, time as dtime
import urllib.request
from collections import deque
from pathlib import Path
from typing import Callable

from . import cli as voice_cli

log = logging.getLogger("marvin.voice.control")

# settings the app edits (voice.json keys), and what they accept
LANGUAGES = (None, "fr", "en")
STT_MODELS = ("tiny", "base", "small", "medium", "large-v3", "turbo")
# Piper voices offered for download (rhasspy/piper-voices); installed ones are listed too
PIPER_VOICES = ("fr_FR-siwis-medium", "fr_FR-tom-medium", "fr_FR-upmc-medium", "fr_FR-gilles-low",
                "en_GB-alan-medium", "en_GB-northern_english_male-medium", "en_US-lessac-medium",
                "en_US-ryan-high")
APP_KEYS = ("llm_model", "stt", "stt_model", "tts", "tts_voice", "language", "wake", "follow_up_s",
            "reminders", "welcome_back", "tools", "internet", "home_place")
DEFAULTS = {"reminders": True, "welcome_back": False}
MAX_HOME_PLACE = 80

OFF, STARTING, ON, STOPPING, ERROR = "off", "starting", "on", "stopping", "error"


class VoiceOff(RuntimeError):
    """A command for the assistant while the voice is not running."""


class VoiceUnavailable(RuntimeError):
    """Something the voice needs is missing. `fix` says what to do."""

    def __init__(self, message: str, fix: str = ""):
        super().__init__(message)
        self.fix = fix


# Live signals from the assistant, passed straight to the app (see VoiceAssistant.add_listener)
LIVE_KINDS = ("level", "utterance", "partial", "say")


def validate(update: dict) -> dict:
    """The app's voice settings, checked; ValueError on anything unexpected. "auto" (language,
    speech recognition model) and "" (voice) mean "the default" and are stored as null."""
    if not isinstance(update, dict):
        raise ValueError("voice settings must be a JSON object")
    out = {}
    for key, v in update.items():
        if key == "llm_model":
            if not isinstance(v, str) or not re.fullmatch(r"[\w.:/@+-]{1,120}", v):
                raise ValueError("llm_model must be an Ollama model name, e.g. qwen3:4b-instruct")
        elif key == "stt":
            if v not in voice_cli.STT_CHOICES:
                raise ValueError(f"stt must be one of {', '.join(voice_cli.STT_CHOICES)}")
        elif key == "stt_model":
            v = None if v in (None, "", "auto") else v
            if v is not None and (not isinstance(v, str) or not re.fullmatch(r"[\w.:/@+-]{1,160}", v)):
                raise ValueError("stt_model must be a Whisper model name (tiny, small, turbo...) or auto")
        elif key == "tts":
            if v not in voice_cli.TTS_CHOICES:
                raise ValueError(f"tts must be one of {', '.join(voice_cli.TTS_CHOICES)}")
        elif key == "tts_voice":
            v = None if v in (None, "") else v
            if v is not None and (not isinstance(v, str) or len(v) > 160 or not v.isprintable()):
                raise ValueError("tts_voice must be a voice name")
        elif key == "language":
            v = None if v in ("auto", "") else v
            if v not in LANGUAGES:
                raise ValueError('language must be "auto", "fr" or "en"')
        elif key == "home_place":
            v = "" if v is None else v
            if not isinstance(v, str):
                raise ValueError("home_place must be a place name, e.g. Nice")
            v = " ".join(v.split())
            if len(v) > MAX_HOME_PLACE or not v.isprintable() or any(c in v for c in "{}<>[]\\\"`"):
                raise ValueError(f"home_place must be a place name of at most {MAX_HOME_PLACE} characters, e.g. Nice")
        elif key in ("wake", "reminders", "welcome_back", "tools", "internet"):
            if not isinstance(v, bool):
                raise ValueError(f"{key} must be true or false")
        elif key == "follow_up_s":
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not 0 <= v <= 30:
                raise ValueError("follow_up_s must be a number of seconds between 0 and 30")
            v = float(v)
        else:
            raise ValueError(f"unknown voice setting {key}")
        out[key] = v
    return out


# ---------------------------------------------------------------- what is available

def ollama_models(host: str, timeout: float = 1.5) -> list[str]:
    """Model names from Ollama's `/api/tags`. Raises VoiceUnavailable when it cannot be reached."""
    try:
        with urllib.request.urlopen(host.rstrip("/") + "/api/tags", timeout=timeout) as r:
            data = json.load(r)
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise VoiceUnavailable(f"Ollama is not running at {host} ({getattr(e, 'reason', e)})",
                               "Install Ollama (https://ollama.com/download or `brew install ollama`), "
                               "then start it: `ollama serve` or the Ollama app.") from None
    return sorted(m.get("name", "") for m in data.get("models", []) if m.get("name"))


def _has_model(names: list[str], model: str) -> bool:
    return model in names or f"{model}:latest" in names


def say_voices() -> list[dict]:
    """macOS `say` voices, [{"name", "locale"}], French and English first; [] elsewhere."""
    if sys.platform != "darwin" or not shutil.which("say"):
        return []
    from .tts import parse_say_voices
    try:
        out = subprocess.run(["say", "-v", "?"], capture_output=True, text=True, timeout=5, check=False).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    voices = [{"name": n, "locale": loc} for n, loc in parse_say_voices(out)]
    return sorted(voices, key=lambda v: (v["locale"][:2] not in ("fr", "en"), v["locale"], v["name"]))


def piper_voices() -> list[dict]:
    """Piper voices: installed ones (in ~/.local/share/marvin/piper) and a few to download on first
    use, [{"name", "installed"}]."""
    from .tts import piper_dir
    try:
        installed = {p.name[:-5] for p in piper_dir().glob("*.onnx")}
    except OSError:
        installed = set()
    names = sorted(installed | set(PIPER_VOICES), key=lambda n: (n[:2] not in ("fr", "en"), n))
    return [{"name": n, "installed": n in installed} for n in names]


def tts_available() -> dict[str, bool]:
    """Which speech synthesis backends can run here."""
    return {"auto": True, "say": sys.platform == "darwin" and bool(shutil.which("say")),
            "piper": importlib.util.find_spec("piper") is not None,
            "espeak": bool(shutil.which("espeak-ng") or shutil.which("espeak"))}


def stt_available() -> dict[str, bool]:
    """Which speech recognition backends can run here ("auto" when one of them can)."""
    from .stt import mlx_available
    out = {"mlx": mlx_available(), "faster-whisper": importlib.util.find_spec("faster_whisper") is not None}
    return {"auto": any(out.values()), **out}


def options(settings: dict) -> dict:
    """What the settings panel offers: models from Ollama, speech recognition, voices."""
    host = settings.get("ollama_host") or voice_cli_default("ollama_host")
    ollama = {"ok": True, "error": "", "fix": "", "host": host}
    try:
        models = ollama_models(host)
    except VoiceUnavailable as e:
        models, ollama = [], {"ok": False, "error": str(e), "fix": e.fix, "host": host}
    tts = tts_available()
    return {
        "llm_models": models, "ollama": ollama,
        "stt_backends": list(voice_cli.STT_CHOICES), "stt_models": ["auto", *STT_MODELS],
        "tts_backends": [{"name": k, "available": v} for k, v in tts.items()],
        "voices": {"piper": piper_voices() if tts["piper"] else [], "say": say_voices()},
        "languages": ["auto", "fr", "en"],
        "platform": sys.platform,
        "tools": tools_catalog(),
    }


def tools_catalog() -> list[dict]:
    """The tools Marvin has, [{"name", "description", "online"}]: the settings panel shows which
    are on (all of them with "tools", the online ones only with "internet")."""
    from .tools import catalog
    return catalog()


def voice_cli_default(key: str):
    from .assistant import VoiceConfig
    return getattr(VoiceConfig(), key)


def audio_preflight(settings: dict, microphone: bool = True) -> None:
    """Raises VoiceUnavailable (with a fix) if the voice's audio half cannot start: the `voice`
    extra (speech recognition, VAD) and, with `microphone`, the computer's microphone. The voice
    sidecar checks this much; the robot's microphone needs no sound card."""
    wanted = ("numpy", "sounddevice", "webrtcvad") if microphone else ("numpy", "webrtcvad")
    missing = [m for m in wanted if importlib.util.find_spec(m) is None]
    if importlib.util.find_spec("faster_whisper") is None and importlib.util.find_spec("mlx_whisper") is None:
        missing.append("faster-whisper")
    if missing:
        raise VoiceUnavailable(f"The voice extra is not installed (missing: {', '.join(missing)})",
                               'In the host folder: pip install -e ".[voice]"')
    if not microphone:
        return
    try:
        import sounddevice as sd
        sd.query_devices(voice_cli._device(settings.get("input_device")), "input")
    except Exception as e:                     # noqa: BLE001 - PortAudio raises various things
        raise VoiceUnavailable(f"No microphone ({e})",
                               "Plug in a microphone, or pick one with input_device in voice.json "
                               "(marvin-host talk --list-devices).") from None


def preflight(settings: dict) -> None:
    """Raises VoiceUnavailable (with a fix) if the voice cannot start: the `voice` extra, a
    microphone, Ollama and the model."""
    audio_preflight(settings)
    host = settings.get("ollama_host") or voice_cli_default("ollama_host")
    model = settings.get("llm_model") or voice_cli_default("llm_model")
    if not _has_model(ollama_models(host), model):
        raise VoiceUnavailable(f"Ollama has no model {model}", f"ollama pull {model}")


# ---------------------------------------------------------------- the controller

class VoiceController:
    """See the module docstring.

    - ``brain``: context for the model and break reminders (optional);
    - ``overrides``: settings from the command line (``run --voice-llm-model ...``); they win over
      voice.json until the same setting is changed in the app;
    - ``factory(config, settings)``: builds the assistant (default: computer mic and speakers);
    - ``check(settings)``: raises VoiceUnavailable when the voice cannot start (default: preflight);
    - ``path``: the settings file (default: voice.json in the config directory);
    - ``clock``: time of the notes it adds to the conversation ("Voice on"...).
    """

    def __init__(self, brain=None, overrides: dict | None = None,
                 factory: Callable | None = None, check: Callable[[dict], None] | None = preflight,
                 path: Path | None = None, history: int = 200, clock: Callable[[], float] = time.time):
        self.brain = brain
        self.overrides = dict(overrides or {})
        self.factory = factory or self._default_factory
        self.check = check
        self.path = path
        self.clock = clock
        self.assistant = None
        self.state = OFF
        self.error = ""
        self.fix = ""
        self.muted = False
        self.transcript: deque[dict] = deque(maxlen=history)
        # ids keep growing across restarts of marvin-host, so an open page never mistakes a new
        # entry for one it already shows
        self._ids = itertools.count(int(time.time() * 1000))
        self._listeners: list[Callable[[str, dict], None]] = []
        self._proactive = None
        self._lock = threading.RLock()          # state
        self._op = threading.Lock()             # one start / stop / restart at a time
        self._thread: threading.Thread | None = None
        self._closed = False
        self.store = None

    # ------------------------------------------------------------ history

    def attach_store(self, store, limit: int | None = None) -> None:
        """Keeps every conversation entry in `store` (an `EventStore`) from now on, and brings back
        today's last entries (at most `limit`, default: the history size) so the conversation
        survives a restart. Entries already in memory are saved too."""
        if self.store is store:
            return
        limit = self.transcript.maxlen if limit is None else limit
        now = self.clock()
        midnight = datetime.combine(datetime.fromtimestamp(now).date(), dtime()).timestamp()
        try:
            for e in list(self.transcript):
                store.add_conversation(e)
            past = store.conversation(midnight, now + 86400, limit)
            top = store.max_conversation_id()
        except Exception:                       # noqa: BLE001 - the voice works without its history
            log.exception("could not read the conversation history")
            return
        with self._lock:
            known = {e["id"] for e in self.transcript}
            merged = sorted([e for e in past if e["id"] not in known] + list(self.transcript), key=lambda e: e["id"])
            self.transcript.clear()
            self.transcript.extend(merged[-self.transcript.maxlen:])
            # ids keep growing: above everything already stored, whatever the clock says
            nxt = next(self._ids)
            self._ids = itertools.count(max(nxt, top + 1))
            self.store = store

    def _keep(self, e: dict) -> None:
        store = self.store
        if store is None:
            return
        try:
            store.add_conversation(e)
        except Exception:                       # noqa: BLE001
            log.exception("could not save a conversation entry")

    # ------------------------------------------------------------ settings

    def settings(self) -> dict:
        """voice.json, then the command-line overrides: what the assistant is built from."""
        return {**voice_cli.load_settings(self.path), **self.overrides}

    def app_settings(self) -> dict:
        """The settings the app edits, with the defaults filled in."""
        s = self.settings()
        from .assistant import VoiceConfig
        d = VoiceConfig()
        out = {"llm_model": d.llm_model, "stt": d.stt, "stt_model": None, "tts": d.tts, "tts_voice": None,
               "language": None, "wake": d.wake, "follow_up_s": d.follow_up_s, "tools": d.tools,
               "internet": d.internet, "home_place": d.home_place, **DEFAULTS}
        out.update({k: s[k] for k in APP_KEYS if k in s})
        return out

    def update_settings(self, update: dict) -> dict:
        """Validates and saves `update` to voice.json (other keys are kept), then restarts the
        assistant if it runs. Returns the new app settings."""
        values = validate(update)
        voice_cli.save_settings(values, self.path)
        for k in values:
            self.overrides.pop(k, None)
        if values:
            self._note("Voice settings saved" + (": restarting" if self.state in (ON, STARTING) else ""))
            if self.state in (ON, STARTING):
                self.restart()
        return self.app_settings()

    # ------------------------------------------------------------ life

    def start(self, wait: bool = False) -> None:
        """Starts the voice in the background (nothing if it runs or is starting)."""
        with self._lock:
            if self.state in (ON, STARTING) or self._closed:
                return
            self._set_state(STARTING)
        self._spawn(self._do_start, wait)

    def stop(self, wait: bool = False) -> None:
        with self._lock:
            if self.state in (OFF, STOPPING):
                return
            if self.state == ERROR:
                self.error = self.fix = ""
                self._set_state(OFF)
                return
            self._set_state(STOPPING)
        self._spawn(self._do_stop, wait)

    def restart(self, wait: bool = False) -> None:
        with self._lock:
            self._set_state(STARTING)

        def run():
            self._do_stop(final=False)
            self._do_start()
        self._spawn(run, wait)

    def close(self) -> None:
        """Stops the voice for good (marvin-host is quitting)."""
        self._closed = True
        th = self._thread
        if th is not None and th is not threading.current_thread():
            th.join(timeout=10)
        with self._op:
            self._teardown()
        with self._lock:
            self.state = OFF

    def wait(self, timeout: float | None = None) -> bool:
        """Waits for the current start / stop to finish. False on timeout."""
        th = self._thread
        if th is None:
            return True
        th.join(timeout)
        return not th.is_alive()

    def _spawn(self, fn, wait: bool) -> None:
        def run():
            with self._op:
                fn()
        th = threading.Thread(target=run, name="marvin-voice-control", daemon=True)
        self._thread = th
        th.start()
        if wait:
            th.join()

    def _do_start(self) -> None:
        if self._closed:
            return
        if self.assistant is not None:
            self._set_state(ON)
            return
        settings = self.settings()
        model = settings.get("llm_model") or voice_cli_default("llm_model")
        try:
            if self.check is not None:
                self.check(settings)
            config = voice_cli._config(None, settings=settings)
            va = self.factory(config, settings)
        except VoiceUnavailable as e:
            return self._failed(str(e), e.fix)
        except Exception as e:                  # noqa: BLE001 - shown in the app, logged here
            log.exception("the voice failed to start")
            return self._failed(f"The voice failed to start: {e}", "See the terminal for details.")
        va.add_listener(self._on_voice)
        if self.muted:
            va.mute(True)
        if self.brain is not None and hasattr(self.brain, "add_listener"):
            from .proactive import ProactiveConfig, ProactiveSpeaker
            self._proactive = ProactiveSpeaker(va, ProactiveConfig(
                still_long=bool(settings.get("reminders", DEFAULTS["reminders"])),
                welcome_back=bool(settings.get("welcome_back", DEFAULTS["welcome_back"]))))
            self.brain.add_listener(self._proactive)
        with self._lock:
            self.assistant = va
            self.error = self.fix = ""
        va.start()
        wake = "say “Marvin, …”" if config.wake else "just talk"
        log.debug("voice on: %s", model)
        self._set_state(ON)
        self._note(f"Voice on: {wake}")

    def _failed(self, message: str, fix: str) -> None:
        log.warning("voice: %s. %s", message, fix)
        with self._lock:
            self.error, self.fix = message, fix
        self._set_state(ERROR)

    def _do_stop(self, final: bool = True) -> None:
        was = self.assistant is not None
        self._teardown()
        if final:
            self._set_state(OFF)
            if was:
                self._note("Voice off")

    def _teardown(self) -> None:
        with self._lock:
            va, self.assistant = self.assistant, None
            pro, self._proactive = self._proactive, None
        if pro is not None and self.brain is not None:
            try:
                self.brain.remove_listener(pro)
            except ValueError:
                pass
        if va is not None:
            va.remove_listener(self._on_voice)
            try:
                va.close()
            except Exception:                   # noqa: BLE001
                log.exception("closing the voice failed")

    def _default_factory(self, config, settings: dict):
        from . import io
        from .assistant import VoiceAssistant
        source = io.MicSource(voice_cli._device(settings.get("input_device")))
        sink = io.SpeakerSink(voice_cli._device(settings.get("output_device")))
        return VoiceAssistant(source, sink, brain=self.brain, config=config)

    # ------------------------------------------------------------ commands

    def _running(self):
        va = self.assistant
        if va is None or self.state != ON:
            raise VoiceOff("Marvin's voice is off")
        return va

    def ask(self, text: str) -> None:
        text = (text or "").strip()
        if not text:
            raise ValueError("nothing to ask")
        if len(text) > 500:
            raise ValueError("a question is at most 500 characters")
        self._running().ask(text)

    def listen_now(self, on: bool = True) -> None:
        """Talk now: opens a listening window (`on`), or closes the one that is open."""
        va = self._running()
        if on:
            va.listen_now()
        elif hasattr(va, "stop_listening"):
            va.stop_listening()

    def stop_speaking(self) -> bool:
        return self._running().stop_speaking()

    def mute(self, muted: bool) -> None:
        """Remembered across restarts; works while the voice is off too (it starts muted)."""
        self.muted = bool(muted)
        va = self.assistant
        if va is not None:
            va.mute(self.muted)
        else:
            self._publish("voice", self.snapshot())

    # ------------------------------------------------------------ events

    def add_listener(self, fn: Callable[[str, dict], None]) -> None:
        """`fn("voice", snapshot())` when the state changes; `fn("transcript", entry)` for each new
        conversation entry. Called from the voice's threads: keep it quick."""
        self._listeners.append(fn)

    def remove_listener(self, fn) -> None:
        if fn in self._listeners:
            self._listeners.remove(fn)

    def _publish(self, kind: str, payload: dict) -> None:
        for fn in list(self._listeners):
            try:
                fn(kind, payload)
            except Exception:                   # noqa: BLE001
                log.exception("voice controller listener failed")

    def snapshot(self) -> dict:
        va = self.assistant
        s = self.settings()
        return {
            "state": self.state,
            "status": va.status if (va is not None and self.state == ON) else "off",
            "muted": self.muted,
            "error": self.error,
            "fix": self.fix,
            "model": s.get("llm_model") or voice_cli_default("llm_model"),
            "wake": bool(s.get("wake", True)),
            "chime": bool(s.get("chime", True)),    # the assistant's own chime when it starts listening
            # seconds left to speak without the name (None outside a listening window)
            "listen_s": (va.listen_remaining() if (va is not None and self.state == ON
                                                    and hasattr(va, "listen_remaining")) else None),
        }

    def _set_state(self, state: str) -> None:
        with self._lock:
            changed = state != self.state
            self.state = state
        if changed:
            self._publish("voice", self.snapshot())

    def _entry(self, kind: str, t: float | None = None, **data) -> dict:
        with self._lock:
            e = {"id": next(self._ids), "t": t or self.clock(), "kind": kind, **data}
            self.transcript.append(e)
        self._keep(e)
        self._publish("transcript", e)
        return e

    def _note(self, text: str) -> None:
        self._entry("note", text=text)

    def _on_voice(self, kind: str, data: dict) -> None:
        if kind == "status":
            self._publish("voice", self.snapshot())
        elif kind == "muted":
            self.muted = data["muted"]
            self._publish("voice", self.snapshot())
        elif kind == "heard":
            extra = {"raw": data["raw"]} if data.get("raw") else {}
            self._entry("heard", data["t"], text=data["text"], language=data.get("language"),
                        source=data.get("source", "voice"), **extra)
        elif kind == "reply":
            lat = {k: round(float(v), 3) for k, v in (data.get("latency") or {}).items()}
            first = None
            if "audio_start" in lat:
                first = round(lat.get("endpoint", 0.0) + lat["audio_start"], 2)
            self._entry("reply", data["t"], text=data["text"], language=data.get("language"),
                        latency=lat, first_word_s=first, interrupted=bool(data.get("interrupted")),
                        proactive=bool(data.get("proactive")), error=data.get("error"),
                        hint=data.get("hint", ""),
                        # what the model was given (the app's "why did Marvin say that")
                        **{k: data[k] for k in ("context", "prompt", "model", "tools") if data.get(k)})
        elif kind == "ignored":
            extra = {"dbfs": data["dbfs"]} if data.get("dbfs") is not None else {}
            self._entry("ignored", data["t"], text=data.get("text", ""), reason=data.get("reason", ""), **extra)
        elif kind in LIVE_KINDS:
            self._publish(kind, data)           # live only: not kept in the transcript

    def recent(self, since: int = 0) -> list[dict]:
        """Conversation entries after id `since`, oldest first."""
        return [e for e in list(self.transcript) if e["id"] > since]

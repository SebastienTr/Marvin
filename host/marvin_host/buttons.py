"""The bench board's buttons and buzzer, on the host side (docs/wiring-bench.md, docs/protocol.md).

- `ButtonActions`, a receiver sink: a robot's `BUTTON` acts on the voice as the app does. Talk is the
  app's Talk now (it opens a listening window, or closes the one that is open); Mute mutes or
  unmutes the microphone. Talk while the voice is off plays the *error* sound on that robot.
- `VoiceChimes`, a voice controller listener: when the voice starts or stops listening, fails, or is
  muted, it sends a `SOUND` to every robot with a buzzer (`HELLO` flag `FLAG_BUZZER`) and no speaker
  (robots with audio hear the voice's own chime). It follows the voice's *chime* setting.

    rx = Receiver(Tee(brain, ButtonActions(controller)))
    controller.add_listener(VoiceChimes(rx))

The Java host does the same (`ButtonFeed`, `VoiceChimes`, `BuzzerChimeService`).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import threading

from . import protocol
from .receiver import Device, Receiver, Sink

log = logging.getLogger("marvin.buttons")


def voice_chime(before: dict | None, after: dict) -> str | None:
    """The sound (protocol.SOUNDS) for a change of the voice's snapshot, or None: one at most.

    error: the voice failed; wake: it starts listening from idle (Talk now, or "Marvin." alone; not the
    follow-up window after an answer); done: a listening window ends (a question was heard, or nothing
    was); wake / done: unmuted / muted. Nothing when the snapshot's chime setting is off.
    """
    if not after.get("chime", True):
        return None
    b = before or {}
    if after.get("state") == "error" and b.get("state") != "error":
        return "error"
    was, now = b.get("status", "off"), after.get("status", "off")
    if now == "listening" and was == "idle":
        return "wake"
    if was == "listening" and now in ("thinking", "idle"):
        return "done"
    if before is not None and bool(b.get("muted")) != bool(after.get("muted")):
        return "done" if after.get("muted") else "wake"
    return None


class VoiceChimes:
    """Voice controller listener: `voice_chime` sounds to the robots with a buzzer and no speaker."""

    def __init__(self, receiver: Receiver):
        self.receiver = receiver
        self.sent = 0
        self._last: dict | None = None
        self._lock = threading.Lock()

    def buzzers(self) -> list[Device]:
        return [d for d in list(self.receiver.devices.values()) if d.hello.has_buzzer and not d.hello.has_audio]

    def __call__(self, kind: str, payload: dict) -> None:
        if kind != "voice":
            return
        with self._lock:
            before, self._last = self._last, dict(payload)
        sound = voice_chime(before, payload)
        if sound is not None:
            self.play(sound)

    def play(self, sound: str, devices: list[Device] | None = None) -> None:
        data = protocol.sound(sound)
        for dev in self.buzzers() if devices is None else devices:
            self.receiver.send(dev, protocol.SOUND, data)
            self.sent += 1


class ButtonActions(Sink):
    """Receiver sink: BUTTON presses act on a voice controller (voice/control.py) as the app's buttons do."""

    def __init__(self, voice, receiver: Receiver | None = None):
        self.voice = voice
        self.receiver = receiver            # for the error sound on the robot; None: no sound
        self.pressed = 0

    def on_button(self, dev: Device, t_us: int, button: int, action: int) -> None:
        if action != protocol.BUTTON_PRESS or button not in protocol.BUTTONS:
            return                          # a button or an action this host does not know
        self.pressed += 1
        name = protocol.BUTTONS[button]
        log.info("%s: %s button", dev.hello.device_name, name)
        # the voice's commands wait a little for its new state: not on the receiver's thread
        threading.Thread(target=self._act, args=(dev, button), name=f"button-{name}", daemon=True).start()

    def _act(self, dev: Device, button: int) -> None:
        from .voice.control import VoiceOff
        try:
            if button == protocol.BUTTON_MUTE:
                self.voice.mute(not self.voice.muted)
                return
            s = self.voice.snapshot()
            self.voice.listen_now(s.get("status") != "listening")
        except VoiceOff:
            log.info("talk button: the voice is off")
            if self.receiver is not None and (dev.hello.has_buzzer or dev.hello.has_audio):
                self.receiver.send(dev, protocol.SOUND, protocol.sound("error"))
        except Exception:                   # noqa: BLE001
            log.exception("button not handled")

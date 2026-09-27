"""Host -> robot link for the face: the brain's events and presence state, sent to the robot's screen.

`FaceLink` listens to a `Brain` and, for every connected robot that has the screen (HELLO board id in
`protocol.SCREEN_BOARDS`: the ESP32-S3 DevKitC and the XIAO ESP32S3 Sense):

- sends each event as a `FACE_EVENT` as soon as the brain emits it;
- sends the presence state as a `FACE_STATE` at 10 Hz, from a background thread.

The robot runs the same face as face.py (firmware/src/face), fed by these two messages. If they
stop for 5 s, the robot's face carries on alone and falls asleep. See docs/protocol.md.

    rx = Receiver(tee_with_brain)
    link = FaceLink(rx, brain)
    link.start()        # ... rx.serve() ...
    link.stop()

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import logging
import threading

from . import protocol
from .brain import Brain
from .events import Event
from .receiver import Device, Receiver

log = logging.getLogger("marvin.link")


class FaceLink:
    def __init__(self, receiver: Receiver, brain: Brain, rate_hz: float = 10.0,
                 boards: frozenset[int] = protocol.SCREEN_BOARDS):
        self.receiver = receiver
        self.brain = brain
        self.period = 1.0 / rate_hz
        self.boards = boards
        self.events_sent = 0
        self.states_sent = 0
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        brain.add_listener(self.on_event)

    def screens(self) -> list[Device]:
        """The connected devices that have a screen."""
        return [d for d in list(self.receiver.devices.values()) if d.hello.board in self.boards]

    def on_event(self, event: Event) -> None:
        """Brain listener: forward the event to every screen right away."""
        payload = protocol.face_event(event.kind)
        if payload is None:
            return
        for dev in self.screens():
            self.receiver.send(dev, protocol.FACE_EVENT, payload)
            self.events_sent += 1

    def send_state(self) -> None:
        """Send the brain's current presence state to every screen (called at `rate_hz` by `start`)."""
        screens = self.screens()
        if not screens:
            return
        payload = protocol.FaceState.from_presence(self.brain.state).encode()
        for dev in screens:
            self.receiver.send(dev, protocol.FACE_STATE, payload)
            self.states_sent += 1

    def start(self) -> FaceLink:
        if self._thread is None:
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, name="face-link", daemon=True)
            self._thread.start()
        return self

    def stop(self) -> None:
        """Stop the state stream and stop listening to the brain."""
        self._stop.set()
        if self._thread is not None:
            self._thread.join()
            self._thread = None
        try:
            self.brain.remove_listener(self.on_event)
        except ValueError:
            pass

    def __enter__(self) -> FaceLink:
        return self.start()

    def __exit__(self, *exc) -> None:
        self.stop()

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self.send_state()
            except Exception:
                log.exception("face state not sent")
            self._stop.wait(self.period)

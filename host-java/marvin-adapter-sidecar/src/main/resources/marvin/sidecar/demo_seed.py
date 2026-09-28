# SPDX-License-Identifier: MIT
"""Writes the demo's history with the Python host's own demo code (ui/demo.py) into a new SQLite file,
which the Java host's demo then imports like any marvin.db: a simulated past week, a few past days of
conversation, and today's scripted conversation (as the scripted voice of `marvin-host ui --demo`
leaves it, without its "voice on" note: the Java host has no voice yet).

    python demo_seed.py OUT.db NOW        (NOW: Unix seconds; run in the repository's host/)
"""
import sys
import tempfile
from pathlib import Path

from marvin_host.brain import Brain
from marvin_host.ui.demo import DemoVoice, seed_conversations, seed_history
from marvin_host.ui.store import EventStore
from marvin_host.voice.control import VoiceController


def todays_script(store: EventStore, now: float) -> None:
    def clock() -> float:
        return now

    with tempfile.TemporaryDirectory() as tmp:
        brain = Brain()
        voice = VoiceController(brain, factory=lambda config, settings: DemoVoice(brain, clock), check=None,
                                path=Path(tmp) / "voice.json", clock=clock)
        voice.start(wait=True)
        voice.close()
        for e in [e for e in voice.transcript if e["kind"] == "note"]:
            voice.transcript.remove(e)
        voice.attach_store(store)           # keeps what is in the transcript


def main() -> None:
    out, now = sys.argv[1], float(sys.argv[2])
    store = EventStore(out)
    try:
        seed_history(store, now)
        seed_conversations(store, now)
        todays_script(store, now)
    finally:
        store.close()


if __name__ == "__main__":
    main()

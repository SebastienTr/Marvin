# SPDX-License-Identifier: MIT
"""Writes the demo's history with the Python host's demo code (ui/demo.py) into a new SQLite file,
which the Java host's demo then imports like any marvin.db: a simulated past week, a few past days of
conversation, and today's scripted conversation.

    python demo_seed.py OUT.db NOW        (NOW: Unix seconds; run in the repository's host/)
"""
import sys

from marvin_host.ui.demo import seed_conversations, seed_history, seed_today
from marvin_host.ui.store import EventStore


def main() -> None:
    out, now = sys.argv[1], float(sys.argv[2])
    store = EventStore(out)
    try:
        seed_history(store, now)
        seed_conversations(store, now)
        seed_today(store, now)
    finally:
        store.close()


if __name__ == "__main__":
    main()

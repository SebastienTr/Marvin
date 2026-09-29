#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Writes a scripted past week into a new Python-host history (marvin.db), for `week.py`: the owner talks with
Marvin on the six days before today, in French and in English, and some of it changes over time (a home in Nice, then
a move to Lille). The Java host imports it on its first start (MARVIN_IMPORT) and memory reads it from there, as it
would for an owner coming from the Python host. The robot's past week comes from the demo's own code.

    python3 host-java/e2e/week_fixture.py OUT.db [NOW]        (run with host/ on the Python path)
"""
import sys
import time
from datetime import datetime, timedelta

from marvin_host.ui.demo import seed_history
from marvin_host.ui.store import EventStore

# (days before today, hour, language, what the owner says, what Marvin answers)
WEEK = [
    (6, 9.2, "fr", "Bonjour Marvin, je m'appelle Alex.", "Bonjour Alex, ravi de te rencontrer."),
    (6, 9.4, "fr", "J'habite à Nice depuis trois ans.", "Nice, c'est une belle ville."),
    (6, 11.0, "fr", "Mon chat s'appelle Pixel, il dort sur mon bureau.", "Pixel a bon goût."),
    (5, 8.9, "en", "What's the weather like today?", "It is mild and sunny."),
    (5, 10.5, "en", "I prefer tea to coffee, by the way.", "Noted, tea it is."),
    (4, 9.1, "fr", "Ma sœur Julie habite à Nantes, elle vient ce week-end.", "Profite bien de sa visite."),
    (4, 14.2, "fr", "Quelle heure est-il ?", "Il est quatorze heures douze."),
    (3, 9.0, "en", "Big news: we moved to Lille last weekend.", "Congratulations on the move!"),
    (3, 9.6, "en", "I work at Northwind, the office is close to the new flat.", "A short commute, nice."),
    (2, 10.0, "fr", "Je suis allergique aux arachides, pense à me le rappeler.", "D'accord, je m'en souviendrai."),
    (2, 17.5, "fr", "Merci Marvin, bonne soirée.", "Bonne soirée !"),
    (1, 9.3, "en", "Can you tell me a joke?", "Why did the robot cross the desk? To get to the charger."),
]


def main() -> None:
    out = sys.argv[1]
    now = float(sys.argv[2]) if len(sys.argv) > 2 else time.time()
    today = datetime.fromtimestamp(now).replace(hour=0, minute=0, second=0, microsecond=0)
    store = EventStore(out)
    try:
        seed_history(store, now)
        n = 0
        for days, hour, lang, said, answer in WEEK:
            t = (today - timedelta(days=days) + timedelta(hours=hour)).timestamp()
            store.add_conversation({"id": int(t * 1000) + n, "t": t, "kind": "heard", "text": said, "language": lang,
                                    "source": "voice", "raw": f"Marvin, {said}"})
            store.add_conversation({"id": int((t + 2) * 1000) + n + 1, "t": t + 2, "kind": "reply", "text": answer,
                                    "language": lang, "latency": {}, "first_word_s": None, "interrupted": False,
                                    "proactive": False, "error": None, "hint": ""})
            n += 2
    finally:
        store.close()
    print(f"{out}: {len(WEEK)} exchanges over 6 days")


if __name__ == "__main__":
    main()

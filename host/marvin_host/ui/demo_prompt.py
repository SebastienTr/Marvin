"""The context and the message the demo's scripted answers pretend they were given (the app's
inspector shows them), as the Python host's language model used to receive them. Demo data only:
Marvin's live persona, context and prompts are the Java host's (host-java `Persona`).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import datetime as dt
from typing import Iterable

from ..events import Event, EventKind, PresenceState
from ..room import JUST_NOW_S, RoomState

LANGUAGE_NAMES = {"fr": "French", "en": "English"}


def _ago(seconds: float) -> str:
    if seconds < 90:
        return f"{seconds:.0f} seconds ago"
    if seconds < 90 * 60:
        return f"{seconds / 60:.0f} minutes ago"
    return f"{seconds / 3600:.1f} hours ago"


def _duration(seconds: float) -> str:
    if seconds < 90:
        return f"{seconds:.0f} seconds"
    if seconds < 90 * 60:
        return f"{seconds / 60:.0f} minutes"
    return f"{seconds / 3600:.1f} hours"


EVENT_TEXT = {
    EventKind.ARRIVED: "someone arrived",
    EventKind.LEFT: "the person left",
    EventKind.APPROACHED: "the person came close to you",
    EventKind.SAT_DOWN: "the person sat down",
    EventKind.STOOD_UP: "the person stood up",
    EventKind.STILL_LONG: "you noticed they had been seated for a long time",
}


def context_facts(state: PresenceState | None, events: Iterable[Event] = (),
                  max_events: int = 5, max_age_s: float = 3600.0) -> list[str]:
    """Plain English facts from the brain, only the reliable ones."""
    if state is None:
        return []
    facts: list[str] = []
    if state.simulated:
        facts.append("Your sensors are simulated for testing (no real radar connected yet): the person below is "
                     "a simulated one walking in a simulated room on a loop, not the one talking to you. If "
                     "asked about the room, the person or their vital signs, give the simulated values and "
                     "say they are simulated.")
    if state.present and state.motion_presence:
        when = "just now" if state.still_s < JUST_NOW_S else _ago(state.still_s)
        facts.append(f"Your motion sensor noticed someone moving near you {when}; it senses movement only, not "
                     "where they are or who they are.")
    elif state.present:
        where = f", about {state.distance_m:.1f} metres from you" if state.distance_m is not None else ""
        facts.append(f"Someone is in front of you{where}.")
        if state.seated and state.seated_s >= 60:
            facts.append(f"They have been seated for {_duration(state.seated_s)}.")
        if state.breath_rate is not None or state.heart_rate is not None:
            if state.breath_rate is not None:
                facts.append(f"Your 60 GHz radar measures their breathing at {state.breath_rate:.0f} per minute, "
                             "right now.")
            if state.heart_rate is not None:
                facts.append(f"Your 60 GHz radar measures their heart rate at {state.heart_rate:.0f} beats per "
                             "minute, right now.")
        elif state.vitals_sensor:
            facts.append("Your 60 GHz vital-signs radar has no reliable reading right now: it needs the person "
                         "seated and still, within about one and a half metres of you, for a few seconds.")
    elif state.motion_presence:
        facts.append("Your motion sensor has noticed no movement near you for a while, so nobody seems to be "
                     "there (someone sitting very still can go unnoticed).")
    else:
        facts.append("Your radar sees nobody right now (you may still be hearing someone out of view).")
    if not state.vitals_sensor and state.breath_rate is None and state.heart_rate is None:
        facts.append("Your 60 GHz vital-signs radar is not connected yet, so you cannot measure breathing or "
                     "heart rate at all for now, wherever the person sits.")
    recent = []
    for ev in events:
        age = (state.t_us - ev.t_us) / 1e6
        if ev.kind in EVENT_TEXT and 0 <= age <= max_age_s:
            recent.append(f"{EVENT_TEXT[ev.kind]} {_ago(age)}")
    if recent:
        facts.append("Recent events: " + "; ".join(recent[-max_events:]) + ".")
    return facts


def room_fact(room: RoomState | None) -> str | None:
    """The room sensors' readings in one line, while they are fresh; None otherwise (or with nothing to say)."""
    if room is None or not room.fresh:
        return None
    parts = []
    if room.temperature_c is not None:
        parts.append(f"{room.temperature_c:.1f} °C")
    if room.humidity is not None:
        parts.append(f"{room.humidity:.0f} % humidity")
    if room.light_word is not None:
        parts.append(room.light_word)
    if room.motion_sensor:
        if room.warming_up:
            parts.append("motion sensor still warming up")
        elif room.motion or (room.motion_age_s is not None and room.motion_age_s < JUST_NOW_S):
            parts.append("movement just now")
        elif room.motion_age_s is not None:
            parts.append(f"last movement {_ago(room.motion_age_s)}")
        else:
            parts.append("no movement seen yet")
    if not parts:
        return None
    source = "simulated sensors, for testing" if room.simulated else "your own sensors"
    return f"The room, from {source}: " + ", ".join(parts) + "."


def context_block(state: PresenceState | None = None, events: Iterable[Event] = (),
                  now: dt.datetime | None = None, home: str = "", room: RoomState | None = None) -> str:
    """`home`: the owner's home place, given so the model calls the weather tool without asking. `room`: the room
    sensors' readings (said only while fresh)."""
    now = now or dt.datetime.now()
    lines = ["Context:", f"- It is {now:%A %d %B %Y, %H:%M} (local time)."]
    if home:
        lines.append(f"- Your owner lives in {home}: that is where the weather tool looks when no place is named.")
    if state is None:
        lines.append("- Your sensors are not connected right now (voice-only mode): you know nothing "
                     "about the room or the person beyond what they tell you.")
    lines += [f"- {f}" for f in context_facts(state, events)]
    fact = room_fact(room)
    if fact:
        lines.append(f"- {fact}")
    return "\n".join(lines)


def user_message(text: str, state: PresenceState | None = None, events: Iterable[Event] = (),
                 now: dt.datetime | None = None, language: str | None = None,
                 context: str | None = None) -> str:
    """What is sent to the model for one question: the context, what the person said, and (last,
    where models weigh it most) the language to answer in. The context is in English, which
    otherwise pulls some models into answering in English. ``context``: a block already built
    with `context_block` (the app shows it with the answer), else it is built here."""
    if context is None:
        context = context_block(state, events, now)
    msg = f"{context}\n\nThe person says: {text}"
    if language:
        msg += f"\n\n(Answer in {LANGUAGE_NAMES.get(language, language)}.)"
    return msg

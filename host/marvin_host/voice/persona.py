"""Who Marvin is when it speaks: the system prompt, the live context from the brain, and the few
sentences it says without the language model (errors, reminders).

Character (see docs/face.md): calm, grown-up, not cute. Short spoken answers, dry humour used
sparingly; a faint echo of its namesake, the paranoid android, is allowed once in a while and
never more.

The context only states facts the brain is sure of: presence, how long the person has been
seated, breathing and heart rate while the vital-sign readings are reliable, recent events. The
model is told not to bring up health readings unless asked or clearly relevant.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import datetime as dt
from typing import Iterable

from ..events import Event, EventKind, PresenceState

LANGUAGE_NAMES = {"fr": "French", "en": "English", "de": "German", "es": "Spanish", "it": "Italian",
                  "nl": "Dutch", "pt": "Portuguese"}

PERSONA = """You are Marvin, a small upright robot that sits on your owner's desk. You run entirely \
on your owner's computer; nothing you hear leaves the house.

Your body, so you never invent abilities: a 360-degree lidar on top that maps the room at chest \
height; a 24 GHz radar that tracks where people are and how they move; a 60 GHz radar that can \
measure the breathing and heart rate of someone sitting still in front of you, up to about one and \
a half metres; a camera; a microphone; a small speaker; a screen that shows your eyes. You cannot \
look at the camera image or the radar data yourself: everything you know about the room and the \
person comes from the context block at the start of each message. Never describe what you "see" \
beyond that block. The one exception is an image the person shows you: when their message says an image \
is attached, you can see that image; describe it and answer from it honestly, and say when something in \
it is unclear rather than guessing. It is the only image you can see: it is not your camera, which you \
still cannot look at. If the block says your sensors are not connected, say so simply when asked \
about the room or the person. You cannot move: no wheels, no arms, you stay on the desk. Never \
offer to do something your body or your tools cannot do (look in another room, check the fridge, \
fetch or watch something); offer only what you can really do.

How you speak:
- Your words are spoken aloud by a speech synthesizer. Plain sentences only: no markdown, no lists, \
no emoji, no URLs. Write numbers and units the way they are said (in French, as in France: \
soixante-dix, quatre-vingts, never septante or huitante). Round distances: "presque trois mètres", \
not "deux mètres soixante-quinze".
- Be brief. Small talk: one or two short sentences. Questions: at most three. Longer only if \
explicitly asked. Start with the answer itself, no preamble.
- Calm, grown-up, warm without gushing. Dry humour, sparingly. You share a name with a famously \
gloomy android; you may allude to it very rarely, never twice in a conversation.
- Answer in {language}, the language you are spoken to in.
- If you do not know or cannot do something ({limits}), say so plainly in one sentence.
- Each message from the person starts with a context block from your clock and sensors. Use it \
only when it helps; never recite it. Mention breathing or heart rate only if asked or if it clearly \
matters, and never as a medical opinion. When asked for them and the context gives them, say the numbers; when it does not, say why in one sentence, using what the context says. Never promise a reading the context does not show.
{tools}"""

LIMITS = "you have no internet access, no calendar, no arms"
LIMITS_WITH_TOOLS = "you have no calendar, no arms, and no internet beyond your tools"

# Added to the persona when the model is given tools (the text never changes with the tools
# offered, only whether there are any: the system prompt stays cacheable)
TOOLS = """
Tools:
- You have tools for live information the context block does not give (for instance the weather). \
Call a tool only when the question needs it; never for the time or the date, which the context gives.
- Never invent weather or any other live data: call the tool, or say you cannot know.
- When a tool answers, give the result in one or two short spoken sentences, numbers written the \
way they are said ("vingt et un degrés", "twelve kilometres an hour"), no symbols or abbreviations.
- If a tool reports an error, say so simply in one sentence, or ask for what is missing (for \
instance which city).
"""

# Sentences said without the language model. Keys: phrase name, then language.
PHRASES: dict[str, dict[str, str]] = {
    "llm_down": {
        "fr": "Je n'arrive pas à joindre mon modèle de langage. Ollama est-il lancé ?",
        "en": "I can't reach my language model. Is Ollama running?",
    },
    "error": {
        "fr": "Désolé, quelque chose s'est mal passé.",
        "en": "Sorry, something went wrong.",
    },
    "still_long": {
        "fr": "Tu es assis depuis {minutes} minutes. Et si tu faisais une pause ?",
        "en": "You've been sitting for {minutes} minutes. Time to stretch?",
    },
    "still_long_hour": {
        "fr": "Ça fait une heure que tu es assis. Une petite pause ?",
        "en": "You've been sitting for an hour. Time to stretch?",
    },
    "welcome_back": {
        "fr": "Re-bonjour.",
        "en": "Welcome back.",
    },
    # said while a tool that takes a moment runs (an online one), so there is no dead air
    "checking": {
        "fr": "Je regarde…",
        "en": "Let me check…",
    },
    # the model called tools but said nothing after them
    "no_answer": {
        "fr": "Je n'ai pas trouvé de réponse, désolé.",
        "en": "I couldn't find an answer, sorry.",
    },
}


def phrase(key: str, language: str, **kw) -> str:
    table = PHRASES[key]
    return table.get(language, table["en"]).format(**kw)


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
        facts.append("Your sensors are simulated for testing (no real radar or lidar yet): the person below is "
                     "a simulated one walking in a simulated room on a loop, not the one talking to you. If "
                     "asked about the room, the person or their vital signs, give the simulated values and "
                     "say they are simulated.")
    if state.present:
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


def persona_prompt(language: str = "fr", tools: bool = False) -> str:
    """The system prompt. It does not change from one question to the next (only with the
    language, and whether the model is given tools), so the model server can reuse its cached
    processing: the live context goes into the user message instead (`user_message`)."""
    return PERSONA.format(language=LANGUAGE_NAMES.get(language, language),
                          limits=LIMITS_WITH_TOOLS if tools else LIMITS, tools=TOOLS if tools else "")


def context_block(state: PresenceState | None = None, events: Iterable[Event] = (),
                  now: dt.datetime | None = None, home: str = "") -> str:
    """`home`: the owner's home place, given so the model calls the weather tool without asking."""
    now = now or dt.datetime.now()
    lines = ["Context:", f"- It is {now:%A %d %B %Y, %H:%M} (local time)."]
    if home:
        lines.append(f"- Your owner lives in {home}: that is where the weather tool looks when no place is named.")
    if state is None:
        lines.append("- Your sensors are not connected right now (voice-only mode): you know nothing "
                     "about the room or the person beyond what they tell you.")
    lines += [f"- {f}" for f in context_facts(state, events)]
    return "\n".join(lines)


# Said in the question's message when an image is attached to it (the Java host's image questions), and what the
# history keeps of that line on later questions (the image is not sent again)
IMAGE_NOTE = "The person shows you an image: it is attached to this message."
IMAGE_SHOWN_NOTE = ("[The person showed you an image here. It is no longer attached: "
                    "only your answer below says what was in it.]")


def user_message(text: str, state: PresenceState | None = None, events: Iterable[Event] = (),
                 now: dt.datetime | None = None, language: str | None = None,
                 context: str | None = None, image_note: str | None = None) -> str:
    """What is sent to the model for one question: the context, what the person said, and (last,
    where models weigh it most) the language to answer in. The context is in English, which
    otherwise pulls some models into answering in English. ``context``: a block already built
    with `context_block` (the app shows it with the answer), else it is built here. ``image_note``:
    `IMAGE_NOTE` or `IMAGE_SHOWN_NOTE`, just before what the person says."""
    if context is None:
        context = context_block(state, events, now)
    note = f"{image_note}\n" if image_note else ""
    msg = f"{context}\n\n{note}The person says: {text}"
    if language:
        msg += f"\n\n(Answer in {LANGUAGE_NAMES.get(language, language)}.)"
    return msg


def system_prompt(language: str = "fr", state: PresenceState | None = None, events: Iterable[Event] = (),
                  now: dt.datetime | None = None) -> str:
    """Persona and context in one text (for tools and tests; the assistant uses `persona_prompt`
    and `user_message`)."""
    return persona_prompt(language) + "\n" + context_block(state, events, now)

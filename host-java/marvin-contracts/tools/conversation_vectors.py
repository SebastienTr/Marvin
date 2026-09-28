# SPDX-License-Identifier: MIT
"""Conversation vectors from the Python host's voice package (persona.py, text.py, tools/, control.py).

The Java conversation service must give the model the same prompts and context, and treat what the model
writes the same way. Written to golden/conversation/vectors.json:

    persona        the system prompt per language, with and without tools; the phrases said without the model
    context        context blocks and user messages for presence states and events (a fixed local time)
    clean          clean_for_speech, strip_thinking, looks_like_payload, strip_payload, payload_tool_calls
    splitter       SentenceSplitter fed piece by piece: the sentences after each piece, then the flush
    language       guess_language
    settings       control.validate: accepted updates (normalised) and error messages; app_settings defaults
    tools          the tools list Ollama receives, the catalog, argument validation, results as the model reads them
    weather        get_weather answers for canned Open-Meteo replies (now, today, tomorrow, qualifiers, errors)
    memory         VoiceAssistant._remember: the history after each turn (halved when full)
    proactive      ProactiveSpeaker: what is said for a sequence of events

    python3 conversation_vectors.py
"""
from __future__ import annotations

import datetime as dt
import json

from _common import GOLDEN, rel, write_json

from marvin_host.events import Event, EventKind, PresenceState
from marvin_host.voice import persona, text as T
from marvin_host.voice import control
from marvin_host.voice.llm import ToolCall
from marvin_host.voice.tools import ToolRegistry, catalog, default_registry
from marvin_host.voice.tools.weather import OpenMeteoWeather, weather_tool

OUT = GOLDEN / "conversation" / "vectors.json"
NOW = dt.datetime(2026, 9, 21, 8, 52)


def state_dict(s: PresenceState | None):
    if s is None:
        return None
    return {"t_us": s.t_us, "present": s.present, "seated": s.seated, "distance_m": s.distance_m,
            "seated_s": s.seated_s, "breath_rate": s.breath_rate, "heart_rate": s.heart_rate,
            "vitals_sensor": s.vitals_sensor, "simulated": s.simulated}


def persona_vectors():
    return {
        "prompts": [{"language": lang, "tools": tools, "text": persona.persona_prompt(lang, tools=tools)}
                    for lang in ("fr", "en", "de", "xx") for tools in (False, True)],
        "phrases": [{"key": k, "language": lang, "text": persona.phrase(k, lang, minutes=42)}
                    for k in persona.PHRASES for lang in ("fr", "en", "de")],
    }


def context_vectors():
    t = 3_600_000_000
    states = [
        None,
        PresenceState(t_us=t),
        PresenceState(t_us=t, present=True, distance_m=0.94),
        PresenceState(t_us=t, present=True, seated=True, seated_s=720.4, distance_m=0.85, vitals_sensor=True),
        PresenceState(t_us=t, present=True, seated=True, seated_s=59.0, breath_rate=14.5, heart_rate=66.5,
                      vitals_sensor=True),
        PresenceState(t_us=t, present=True, seated=True, seated_s=5400.0, breath_rate=12.0, vitals_sensor=True,
                      simulated=True),
        PresenceState(t_us=t, present=False, simulated=True, vitals_sensor=True),
        PresenceState(t_us=t, present=True, seated=True, seated_s=89.5, heart_rate=70.49),
    ]
    events = [Event(EventKind.ARRIVED, t - 5_000_000_000), Event(EventKind.ARRIVED, t - 3_000_000_000),
              Event(EventKind.SAT_DOWN, t - 2_000_000_000), Event(EventKind.VITALS_ACQUIRED, t - 1_000_000_000),
              Event(EventKind.STOOD_UP, t - 400_000_000), Event(EventKind.APPROACHED, t - 89_000_000),
              Event(EventKind.SAT_DOWN, t - 5_399_000_000 + 5_400_000_000 - 91_000_000),
              Event(EventKind.STILL_LONG, t - 30_000_000), Event(EventKind.LEFT, t - 2_500_000),
              Event(EventKind.ARRIVED, t + 1_000_000)]
    out = []
    for i, s in enumerate(states):
        for evs in ([], events):
            for home in ("", "Nice"):
                block = persona.context_block(s, evs, now=NOW, home=home)
                out.append({"state": state_dict(s), "events": [{"kind": e.kind.value, "t_us": e.t_us} for e in evs],
                            "home": home, "block": block,
                            "message_fr": persona.user_message("Quelle heure est-il ?", language="fr", context=block),
                            "message_none": persona.user_message("Hello", language=None, context=block)})
    return {"now": NOW.isoformat(), "cases": out}


CLEAN = [
    "Bonjour ! Il fait **beau** aujourd'hui.",
    "# Titre\n- un\n- deux\n1. trois\n2) quatre",
    "Voir [le site](https://example.com) ou https://example.org/x?y=1 😀👍",
    '{"name": "get_weather", "arguments": {"day": "now"}}',
    'Je regarde. {"name": "get_weather"} Voilà.',
    '<tool_call>{"name": "get_weather", "arguments": {}}</tool_call>',
    "```json\n{\"a\": 1}\n```Après le code.",
    "<think>hmm, let me think</think>La réponse est 42.",
    "I should check the weather first.</think>Il fait vingt degrés.",
    "Il fait beau </think",
    'Il reste "key": "value" dans le texte',
    "Liste vide [] et [ , ] ici",
    "Température : 21 °C | vent ~ 10 km/h > moyenne",
    "Tab\tand   spaces   here",
    "",
    "   ",
]
PAYLOADS = [
    "", "   ", "{", "[1, 2]", "```json", "<tool_c", "<tool_call>", "<|tool_call|>", "<function_call>x", "<functions>",
    "<", "<t", "<b>bold</b>", "Bonjour", "  Il fait beau", "`", "``", "```", "<TOOL_CALL>",
]
TOOL_TEXTS = [
    '{"name": "get_weather", "arguments": {"day": "tomorrow"}}',
    '[{"name": "get_weather", "arguments": {}}, {"function": {"name": "x", "arguments": "{\\"a\\": 1}"}}]',
    '<tool_call>\n{"name": "get_weather", "parameters": {"place": "Paris"}}\n</tool_call>',
    '```json\n{"name": "get_weather", "arguments": {"place": "Nice", "day": "now"}}\n```',
    'Sure. {"not": "a call"} then {"name": 3} and {"name": "ok", "id": "c1"}',
    '{"name": "broken", "arguments": {',
    'no json here',
    '{"name": "unicode", "arguments": {"place": "Saint-Étienne", "n": 1.5e3, "b": true, "z": null}}',
]
THINK = [
    "<think>reasoning</think>Answer.",
    "reasoning</think>\n\nAnswer again.",
    "a</think>b</think>  c",
    "no thinking here",
    "<think>open only",
    "x</think",
    "x</think >y",
]
SPLIT = [
    ["La capitale de la France, ", "c'est Paris. ", "Elle est grande."],
    ["Paris, bien sûr, la ville lumière. Oui."],
    ["Oui. ", "C'est ça. ", "Très bien, merci beaucoup."],
    ["M. Dupont est là. ", "Dr. Who aussi. Fin."],
    ["Il fait 3.5 degrés", " dehors. Bon."],
    ["Bonjour", ".", " Comment", " allez", "-vous ?", " Bien."],
    ["Ligne un\nLigne deux\n\nLigne trois"],
    ["Il est 14:30, ", "et il fait beau; ", "vraiment beau: ", "super."],
    ["Hello!", " How are you?", " I'm fine…", " Thanks."],
    ["« Bonjour. » ", "Et ensuite ? ", "Rien."],
    ["A. ", "B. ", "C."],
    ["Voici la liste — ", "un, deux, trois."],
    ["The answer is 42", "."],
    ["etc. ", "et puis voilà. ", "Ok."],
    ["Il fait vingt et un degrés à Nice, avec un ciel dégagé et un vent léger."],
    ["Ok"],
]
LANG = ["Quelle heure est-il ?", "What time is it?", "Je ne sais pas", "the the the", "oui", "Is it raining in Paris?",
        "C'est l'heure de la pause, tu es là depuis longtemps", "Marvin", "", "yes no oui non"]


def clean_vectors():
    return {
        "clean_for_speech": [{"in": s, "out": T.clean_for_speech(s)} for s in CLEAN + TOOL_TEXTS + THINK],
        "strip_payload": [{"in": s, "out": T.strip_payload(s)} for s in CLEAN + TOOL_TEXTS],
        "strip_thinking": [{"in": s, "out": T.strip_thinking(s)} for s in THINK + CLEAN],
        "looks_like_payload": [{"in": s, "out": T.looks_like_payload(s)} for s in PAYLOADS + CLEAN],
        "payload_tool_calls": [{"in": s, "out": T.payload_tool_calls(s),
                                "calls": [None if c is None else {"name": c.name, "arguments": c.arguments, "id": c.id}
                                          for c in map(ToolCall.parse, T.payload_tool_calls(s))]}
                               for s in TOOL_TEXTS + CLEAN],
    }


def splitter_vectors():
    out = []
    for pieces in SPLIT:
        for min_chars, first in ((12, 3), (0, 0), (12, 0)):
            sp = T.SentenceSplitter(min_chars=min_chars, first_clause_words=first)
            steps = [sp.feed(p) for p in pieces]
            out.append({"pieces": pieces, "min_chars": min_chars, "first_clause_words": first, "steps": steps,
                        "flush": sp.flush()})
    return out


SETTINGS_OK = [
    {"llm_model": "qwen3:8b"}, {"stt": "mlx"}, {"stt_model": "auto"}, {"stt_model": ""}, {"stt_model": "large-v3"},
    {"tts": "piper"}, {"tts_voice": ""}, {"tts_voice": "fr_FR-siwis-medium"}, {"language": "auto"}, {"language": ""},
    {"language": "fr"}, {"language": None}, {"home_place": "  Saint-Laurent   du  Var "}, {"home_place": None},
    {"wake": False}, {"reminders": True}, {"welcome_back": True}, {"tools": False}, {"internet": False},
    {"follow_up_s": 0}, {"follow_up_s": 30}, {"follow_up_s": 2.5},
    {"llm_model": "hf.co/user/model:Q4_K_M", "language": "en", "follow_up_s": 7},
]
SETTINGS_BAD = [
    {"llm_model": ""}, {"llm_model": "bad name"}, {"llm_model": 3}, {"stt": "whisper"}, {"stt_model": "a b"},
    {"stt_model": 5}, {"tts": "festival"}, {"tts_voice": "x" * 161}, {"tts_voice": "a\nb"}, {"tts_voice": 1},
    {"language": "de"}, {"language": 1}, {"home_place": 5}, {"home_place": "x" * 81}, {"home_place": "Nice{}"},
    {"home_place": "Ni\u0007ce"}, {"wake": "yes"}, {"wake": 1}, {"tools": None}, {"follow_up_s": "soon"},
    {"follow_up_s": True}, {"follow_up_s": 31}, {"follow_up_s": -1}, {"volume": 3},
]


def settings_vectors():
    ok = [{"in": u, "out": control.validate(u)} for u in SETTINGS_OK]
    bad = []
    for u in SETTINGS_BAD:
        try:
            control.validate(u)
            bad.append({"in": u, "error": None})
        except ValueError as e:
            bad.append({"in": u, "error": str(e)})
    ctl = control.VoiceController(check=None, path=GOLDEN / "does-not-exist.json")
    return {"ok": ok, "bad": bad, "app_defaults": ctl.app_settings()}


def fake_fetch(replies):
    def fetch(url, params, timeout):
        key = "geo" if "geocoding" in url else "forecast"
        r = replies[key]
        if isinstance(r, Exception):
            raise r
        return r
    return fetch


FORECAST = {"current": {"time": "2026-09-21T08:45", "temperature_2m": 20.5, "apparent_temperature": 21.5,
                        "weather_code": 1, "wind_speed_10m": 12.44, "precipitation": 0.25},
            "daily": {"time": ["2026-09-21", "2026-09-22"], "weather_code": [3, 61],
                      "temperature_2m_max": [24.5, 19.4], "temperature_2m_min": [15.5, 13.6],
                      "precipitation_probability_max": [10, 80]}}
GEO = {"results": [{"name": "Paris", "country": "France", "country_code": "FR", "admin1": "Île-de-France",
                    "latitude": 48.85341, "longitude": 2.3488},
                   {"name": "Paris", "country": "United States", "country_code": "US", "admin1": "Texas",
                    "latitude": 33.66094, "longitude": -95.55551}]}


def weather_vectors():
    cases = []
    for args, geo, forecast, home in [
        ({}, GEO, FORECAST, "Paris"),
        ({"day": "today"}, GEO, FORECAST, "Paris"),
        ({"day": "tomorrow", "place": "Paris, Texas"}, GEO, FORECAST, ""),
        ({"place": "Paris, us"}, GEO, FORECAST, ""),
        ({"place": "Paris, nowhere"}, GEO, FORECAST, ""),
        ({}, GEO, FORECAST, ""),
        ({"place": "Nicee"}, {"results": []}, FORECAST, ""),
        ({"place": "Nice"}, {"error": True, "reason": "bad request"}, FORECAST, ""),
        ({"place": "Nice"}, GEO, {"current": {}}, ""),
        ({"place": "Nice", "day": "TOMORROW"}, GEO, {"current": {"weather_code": 42}, "daily": {"time": ["x", "bad"]}}, ""),
        ({"place": "Nice"}, OSError("connection refused"), FORECAST, ""),
        ({"day": "later"}, GEO, FORECAST, "Nice"),
    ]:
        reg = ToolRegistry([weather_tool(home, fetch=fake_fetch({"geo": geo, "forecast": forecast}))])
        res = reg.call("get_weather", args, {"language": "fr"})
        cases.append({"arguments": args, "geo": None if isinstance(geo, Exception) else geo,
                      "geo_error": str(geo) if isinstance(geo, Exception) else None,
                      "forecast": forecast, "home": home, "ok": res.ok, "content": res.content, "error": res.error,
                      "arguments_checked": res.arguments})
    return cases


def tools_vectors():
    reg = default_registry(home_place="Nice")
    calls = []
    for name, args in [("get_weather", "{\"day\": \"now\"}"), ("get_weather", "not json"), ("get_weather", [1]),
                       ("get_weather", {"day": "soon"}), ("get_weather", {"place": 12}), ("nope", {}),
                       ("get_weather", {"place": "x" * 81})]:
        res = ToolRegistry([weather_tool("Nice", fetch=fake_fetch({"geo": GEO, "forecast": FORECAST}))]).call(name, args)
        calls.append({"name": name, "arguments": args, "ok": res.ok, "content": res.content, "record_error": res.error})
    off = ToolRegistry([weather_tool()], internet=False)
    return {"ollama_tools": reg.ollama_tools(), "ollama_tools_json": json.dumps(reg.ollama_tools()),
            "catalog": catalog(), "calls": calls,
            "offline_unknown": off.call("get_weather", {}).content,
            "disabled_unknown": ToolRegistry([weather_tool()], enabled=False).call("get_weather", {}).content,
            "record": ToolRegistry([weather_tool("Paris", fetch=fake_fetch({"geo": GEO, "forecast": FORECAST}))])
            .call("get_weather", {}).record() | {"seconds": 0.0}}


class _Memory:
    """VoiceAssistant._remember on a bare object."""
    def __init__(self, turns):
        from types import SimpleNamespace
        self.history = []
        self.config = SimpleNamespace(memory_turns=turns)
        self._last_turn = 0.0


def memory_vectors():
    from marvin_host.voice.assistant import VoiceAssistant
    m = _Memory(4)
    steps = []
    for i in range(7):
        exchange = ([{"role": "assistant", "content": "", "tool_calls": [{"function": {"name": "get_weather", "arguments": {}}}]},
                     {"role": "tool", "content": "{}", "tool_name": "get_weather"}] if i % 3 == 1 else [])
        VoiceAssistant._remember(m, f"q{i}", f"a{i}", exchange)
        steps.append([(x["role"], x["content"]) for x in m.history])
    return {"turns": 4, "steps": steps}


def proactive_vectors():
    from marvin_host.voice.proactive import ProactiveConfig, ProactiveSpeaker

    class A:
        language = "fr"

        def say(self, text, lang):
            return True
    clock = [0.0]
    sp = ProactiveSpeaker(A(), ProactiveConfig(still_long=True, welcome_back=True), clock=lambda: clock[0])
    seq = [(0, Event(EventKind.STILL_LONG, 1, data={"seated_s": 3000.0})),
           (60, Event(EventKind.STILL_LONG, 2, data={"seated_s": 3600.0})),
           (700, Event(EventKind.STILL_LONG, 3, data={"seated_s": 3629.0})),
           (1400, Event(EventKind.LEFT, 10_000_000)),
           (1500, Event(EventKind.ARRIVED, 10_000_000 + 1_900_000_000)),
           (2200, Event(EventKind.LEFT, 2_000_000_000)),
           (2300, Event(EventKind.ARRIVED, 2_000_000_000 + 600_000_000)),
           (3000, Event(EventKind.STILL_LONG, 4, data={"seated_s": 4470.0}))]
    out = []
    for t, ev in seq:
        clock[0] = t
        n = len(sp.spoken)
        sp(ev)
        out.append({"t": t, "kind": ev.kind.value, "t_us": ev.t_us, "data": ev.data,
                    "said": sp.spoken[-1] if len(sp.spoken) > n else None})
    return out


def main() -> None:
    out = {
        "about": "Generated by conversation_vectors.py from the Python host's voice package. Do not edit.",
        "persona": persona_vectors(),
        "context": context_vectors(),
        "clean": clean_vectors(),
        "splitter": splitter_vectors(),
        "language": [{"in": s, "out": T.guess_language(s)} for s in LANG],
        "settings": settings_vectors(),
        "tools": tools_vectors(),
        "weather": weather_vectors(),
        "memory": memory_vectors(),
        "proactive": proactive_vectors(),
    }
    print(rel(write_json(OUT, out)))


if __name__ == "__main__":
    main()

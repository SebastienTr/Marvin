"""Tools the model can call: the registry (schemas, filtering, validation, safe calls), the
assistant's tool loop with a scripted model, the weather tool with a fake HTTP layer, Ollama's
streamed tool calls with a fake server, the settings and what the app's inspector receives.
No network, no model.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

import pytest

from marvin_host.voice import persona
from marvin_host.voice import cli as voice_cli
from marvin_host.voice.assistant import VoiceAssistant, VoiceConfig
from marvin_host.voice.control import VoiceController, validate
from marvin_host.voice.io import NullSink
from marvin_host.voice.llm import FakeLLM, OllamaLLM, ToolCall
from marvin_host.voice.stt import FakeSTT
from marvin_host.voice.text import SentenceSplitter, clean_for_speech, payload_tool_calls
from marvin_host.voice.tools import Tool, ToolError, ToolRegistry, default_registry, validate_arguments
from marvin_host.voice.tools.weather import FORECAST_URL, GEOCODING_URL, OpenMeteoWeather, weather_tool
from marvin_host.voice.tts import FakeTTS
from marvin_host.voice.vad import EnergyVad
from test_voice import ScriptSource


# ---------------------------------------------------------------- tools used in these tests

def _echo(text: str, times: int = 1) -> str:
    return " ".join([text] * times)


ECHO = Tool("echo", "Repeat a text.", {"type": "object", "properties": {
    "text": {"type": "string"}, "times": {"type": "integer"}}, "required": ["text"]}, _echo)
ECHO_REORDERED = Tool("echo", "Repeat a text.", {"required": ["text"], "properties": {
    "times": {"type": "integer"}, "text": {"type": "string"}}, "type": "object"}, _echo)


def _online(**kw):
    return {"temperature_c": 21, "conditions": "partly cloudy"}


ONLINE = Tool("get_weather", "The weather.", {"type": "object", "properties": {
    "place": {"type": "string"}, "day": {"type": "string", "enum": ["now", "today", "tomorrow"]}}},
    _online, online=True)


def registry(**kw) -> ToolRegistry:
    return ToolRegistry([ONLINE, ECHO], **kw)


# ---------------------------------------------------------------- registry

def test_schemas_are_byte_identical_and_sorted():
    a, b = ToolRegistry([ECHO, ONLINE]), ToolRegistry([ONLINE, ECHO_REORDERED])
    first = json.dumps(a.ollama_tools())
    assert a.ollama_tools() is a.ollama_tools()                     # the same object every request
    assert json.dumps(a.ollama_tools()) == first == json.dumps(b.ollama_tools())
    assert [t["function"]["name"] for t in a.ollama_tools()] == ["echo", "get_weather"]
    assert a.ollama_tools()[0]["type"] == "function"
    assert a.ollama_tools()[0]["function"]["parameters"]["required"] == ["text"]
    # the real registry too
    assert json.dumps(default_registry().ollama_tools()) == json.dumps(default_registry().ollama_tools())


def test_disabled_and_offline_filtering():
    assert registry(enabled=False).ollama_tools() is None
    off = registry(internet=False)
    assert [t["function"]["name"] for t in off.ollama_tools()] == ["echo"]
    res = off.call("get_weather", {})
    assert not res.ok and "unknown tool 'get_weather'" in res.error and "echo" in res.error
    assert [s["on"] for s in off.status()] == [True, False]
    r = registry()
    before = r.ollama_tools()
    r.internet = False                                               # a setting change: a new list
    assert r.ollama_tools() != before and len(r.ollama_tools()) == 1
    assert registry(enabled=False).call("echo", {"text": "x"}).error.endswith("no tools are available")


def test_argument_validation():
    schema = ECHO.parameters
    assert validate_arguments(schema, {"text": "hi", "times": "2", "junk": 1, "none": None}) == {"text": "hi", "times": 2}
    assert validate_arguments(schema, '{"text": "hi"}') == {"text": "hi"}
    assert validate_arguments(ONLINE.parameters, {"day": "Tomorrow"}) == {"day": "tomorrow"}
    assert validate_arguments(ONLINE.parameters, None) == {}
    for bad, msg in [({}, "missing argument: text"), ({"text": 3}, "must be a string"),
                     ({"text": "a", "times": 1.5}, "whole number"), ({"text": "a", "times": True}, "number"),
                     ("{nope", "not valid JSON"), ([1], "JSON object")]:
        with pytest.raises(ToolError, match=msg):
            validate_arguments(schema, bad)
    with pytest.raises(ToolError, match="one of now, today, tomorrow"):
        validate_arguments(ONLINE.parameters, {"day": "yesterday"})
    res = registry().call("echo", {"times": 2})
    assert not res.ok and json.loads(res.content) == {"error": "missing argument: text"}


def test_tool_failures_become_error_results():
    def boom():
        raise RuntimeError("disk on fire")

    def expected():
        raise ToolError("no such thing")

    def slow():
        time.sleep(2)
        return "late"

    empty = {"type": "object", "properties": {}}
    r = ToolRegistry([Tool("boom", "x", empty, boom), Tool("expected", "x", empty, expected),
                      Tool("slow", "x", empty, slow, timeout=0.1), ECHO])
    res = r.call("boom")
    assert not res.ok and "RuntimeError: disk on fire" in res.error and "error" in json.loads(res.content)
    assert r.call("expected").error == "no such thing"
    t = time.monotonic()
    res = r.call("slow")
    assert not res.ok and "no answer within 0.1 s" in res.error and time.monotonic() - t < 1
    ok = r.call("echo", {"text": "hi", "times": 2})
    assert ok.ok and ok.content == "hi hi" and ok.record()["result"] == "hi hi"
    assert [c["name"] for c in r.calls] == ["boom", "expected", "slow", "echo"]
    assert set(r.calls[-1]) == {"name", "arguments", "ok", "seconds", "result"}
    assert set(r.calls[0]) == {"name", "arguments", "ok", "seconds", "error"}
    with pytest.raises(ValueError):
        ToolRegistry([Tool("bad name", "x", empty, boom)])


# ---------------------------------------------------------------- the assistant's tool loop

def ask(question: str, replies, tools: ToolRegistry | None = None, va=None, **cfg):
    """Asks a typed question of an assistant with a scripted model; returns (va, t) with the model,
    the speech, and the listener's events."""
    llm = replies if isinstance(replies, FakeLLM) else FakeLLM(replies)
    events = []
    if va is None:
        src = ScriptSource([])
        va = VoiceAssistant(src, NullSink(), config=VoiceConfig(follow_up_s=0.0, **cfg), stt=FakeSTT([]), llm=llm,
                            tts=FakeTTS(), vad=EnergyVad(), tools=tools if tools is not None else registry())
        va.add_listener(lambda kind, data: events.append((kind, data)))
        src.assistant = va
    va.ask(question, language="fr")
    assert va.wait_idle(timeout=5)
    return va, SimpleNamespace(llm=va.llm, tts=va.tts, events=events)


def replies(t) -> list[dict]:
    return [d for k, d in t.events if k == "reply"]


def test_one_tool_call_then_the_answer():
    va, t = ask("Quel temps fait-il ?", [ToolCall("get_weather", {"day": "now"}),
                                         "Il fait vingt et un degrés, partiellement nuageux."])
    first, second = t.llm.calls
    assert t.llm.tools[0] is t.llm.tools[1] is va.tools.ollama_tools()      # the same tools every request
    assert "Tools:" in first[0]["content"] and first[0] == second[0]          # the cached system prompt
    assert second[:len(first)] == first                                       # the prefix is unchanged
    call, result = second[len(first):]
    assert call == {"role": "assistant", "content": "",
                    "tool_calls": [{"function": {"name": "get_weather", "arguments": {"day": "now"}}}]}
    assert result["role"] == "tool" and result["tool_name"] == "get_weather"
    assert json.loads(result["content"])["temperature_c"] == 21
    # an online tool: a filler first, then the answer
    assert [s for s, _ in t.tts.said] == ["Je regarde…", "Il fait vingt et un degrés,", "partiellement nuageux."]
    reply = replies(t)[0]
    assert reply["text"] == "Je regarde… Il fait vingt et un degrés, partiellement nuageux."
    assert reply["tools"][0]["name"] == "get_weather" and reply["tools"][0]["ok"]
    assert "temperature_c" in reply["tools"][0]["result"]
    lat = reply["latency"]
    assert {"llm_first_token", "tools", "llm_first_token_2", "first_chunk", "filler_start", "audio_start"} <= set(lat)
    assert lat["first_chunk"] >= lat["llm_first_token"]
    # the whole exchange is remembered, the filler is not
    assert [m["role"] for m in va.history] == ["user", "assistant", "tool", "assistant"]
    assert va.history[-1]["content"] == "Il fait vingt et un degrés, partiellement nuageux."


def test_offline_tool_has_no_filler_and_no_tools_means_no_tools_prompt():
    va, t = ask("Répète bonjour.", [ToolCall("echo", {"text": "bonjour"}), "Bonjour."])
    assert [s for s, _ in t.tts.said] == ["Bonjour."]
    va, t = ask("Bonjour.", ["Bonjour."], tools=registry(enabled=False))
    assert t.llm.tools == [None] and "Tools:" not in t.llm.calls[0][0]["content"]
    assert "no internet access" in t.llm.calls[0][0]["content"]


def test_unknown_tool_is_reported_to_the_model():
    va, t = ask("Allume la lumière.", [ToolCall("turn_on_lights", {}), "Je n'ai pas d'interrupteur, hélas."])
    tool_msg = t.llm.calls[1][-1]
    assert tool_msg["role"] == "tool" and "unknown tool 'turn_on_lights'" in tool_msg["content"]
    assert replies(t)[0]["tools"][0]["ok"] is False
    assert " ".join(s for s, _ in t.tts.said) == "Je n'ai pas d'interrupteur, hélas."


def test_tool_rounds_are_capped():
    llm = FakeLLM(lambda messages: [ToolCall("echo", {"text": "encore"})])
    va, t = ask("Boucle.", llm, max_tool_rounds=3)
    assert len(llm.calls) == 4                                   # 3 rounds of tools, then it had to answer
    assert len(replies(t)[0]["tools"]) == 3
    assert [s for s, _ in t.tts.said] == [persona.phrase("no_answer", "fr")]
    assert [m["role"] for m in va.history].count("tool") == 3


def test_tool_call_written_as_text_is_run_and_never_spoken():
    payload = '<tool_call>\n{"name": "get_weather", "arguments": {"place": "Nice"}}\n</tool_call>'
    va, t = ask("Météo à Nice ?", [payload, "Vingt et un degrés à Nice."])
    spoken = " ".join(s for s, _ in t.tts.said)
    assert "{" not in spoken and "get_weather" not in spoken and "tool_call" not in spoken
    assert t.llm.calls[1][-2]["tool_calls"][0]["function"] == {"name": "get_weather", "arguments": {"place": "Nice"}}
    assert spoken.endswith("Vingt et un degrés à Nice.")
    # JSON that is not a tool call is not spoken either; prose starting with a bracket is
    va, t = ask("Et ça ?", ['{"temperature": 21}'])
    assert [s for s, _ in t.tts.said] == [persona.phrase("no_answer", "fr")]
    va, t = ask("Et ça ?", ["[soupir] Bon, d'accord."])
    assert " ".join(s for s, _ in t.tts.said) == "[soupir] Bon, d'accord."


def test_history_stays_append_only_across_tool_exchanges_and_halves_by_turns():
    llm = FakeLLM(lambda messages: (
        "Voilà." if messages[-1]["role"] == "tool" else
        [ToolCall("echo", {"text": "x"})] if "outil" in messages[-1]["content"] else "D'accord."))
    va, _ = ask("Question avec outil ?", llm, memory_turns=4)
    prefixes = []
    for i, q in enumerate(["Deux ?", "Trois avec outil ?", "Quatre ?"]):
        before = [dict(m) for m in va.history]
        ask(q, llm, va=va)
        assert va.history[:len(before)] == before              # it only grew at the end
        prefixes.append(llm.calls[-1][1:1 + len(before)] == before)
    assert all(prefixes)                                       # every request starts with the same history
    assert [m["role"] for m in va.history].count("user") == 4 and len(va.history) == 4 * 2 + 2 * 2
    ask("Cinq ?", llm, va=va)                                  # 5 turns > 4: the older half goes, whole turns
    roles = [m["role"] for m in va.history]
    assert roles[0] == "user" and roles.count("user") == 2
    assert "Quatre" in va.history[0]["content"] and "tool" not in roles
    # halving with a tool exchange in the kept half keeps it whole
    va2, _ = ask("Un ?", FakeLLM(["a"]), memory_turns=2)
    va2._remember("q2", "a2")
    va2._remember("q3", "a3", [{"role": "assistant", "content": "", "tool_calls": []},
                               {"role": "tool", "content": "{}", "tool_name": "echo"}])
    assert [m["role"] for m in va2.history] == ["user", "assistant", "tool", "assistant"]
    assert va2.history[0]["content"] == "q3"


def test_the_cleaner_never_speaks_a_payload():
    for text in ['{"name": "get_weather", "arguments": {"place": "Nice"}}', '{"name": "get_weather",',
                 '"arguments": {"place": "Nice"}}', "<tool_call>", "</tool_call>",
                 '```json\n{"name": "get_weather"}\n```', '[{"name": "a", "arguments": {}}]']:
        assert clean_for_speech(text) == "", text
    assert clean_for_speech('Il a dit "oui": alors d\'accord.') == 'Il a dit "oui": alors d\'accord.'
    assert clean_for_speech("Il fait 21 °C, 3,5 km/h.") == "Il fait 21 °C, 3,5 km/h."
    # streamed in pieces, the payload is cut into fragments: none of them is spoken
    sp = SentenceSplitter()
    payload = '{"name": "get_weather", "arguments": {"place": "Nice", "day": "now"}}'
    chunks = [c for i in range(0, len(payload), 5) for c in sp.feed(payload[i:i + 5])] + sp.flush()
    assert chunks and all(clean_for_speech(c) == "" for c in chunks)
    assert payload_tool_calls("Sure. " + payload) == [json.loads(payload)]
    assert payload_tool_calls('{"temperature": 3}') == []


def test_ollama_tool_call_shapes():
    assert ToolCall.parse({"function": {"name": "a", "arguments": '{"x": 1}'}}) == ToolCall("a", {"x": 1})
    assert ToolCall.parse({"name": "a", "parameters": {"x": 1}, "id": "c1"}) == ToolCall("a", {"x": 1}, "c1")
    assert ToolCall.parse({"function": {"arguments": {}}}) is None
    assert ToolCall("a", {"x": 1}, "c1").message() == {"function": {"name": "a", "arguments": {"x": 1}}, "id": "c1"}


# ---------------------------------------------------------------- Ollama, streamed tool calls (fake server)

class _ToolOllama(BaseHTTPRequestHandler):
    requests: list[dict] = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        self._send(200, {"models": [{"name": "qwen3:4b-instruct"}, {"name": "gemma3:4b"}]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        type(self).requests.append(body)
        if body["model"] == "gemma3:4b" and "tools" in body:
            return self._send(400, {"error": "registry.ollama.ai/library/gemma3:4b does not support tools"})
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.end_headers()
        if "tools" in body and body["messages"][-1]["role"] == "user" and "weather" in body["messages"][-1]["content"]:
            lines = [{"message": {"role": "assistant", "content": "", "tool_calls": [
                {"id": "call_1", "function": {"index": 0, "name": "get_weather", "arguments": {"day": "tomorrow"}}}]},
                "done": False}]
        else:
            lines = [{"message": {"role": "assistant", "content": "Sunny. "}, "done": False}]
        for line in lines + [{"message": {"role": "assistant", "content": ""}, "done": True}]:
            try:
                self.wfile.write((json.dumps(line) + "\n").encode())
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return

    def _send(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@pytest.fixture
def tool_ollama():
    _ToolOllama.requests = []
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _ToolOllama)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def test_ollama_streams_tool_calls_with_the_same_tools_as_the_warm_up(tool_ollama):
    tools = default_registry().ollama_tools()
    llm = OllamaLLM(host=tool_ollama)
    system = persona.persona_prompt("en", tools=True)
    assert llm.warm_up(system, persona.user_message("Bonjour."), tools=tools) is not None
    pieces = list(llm.stream_chat([{"role": "system", "content": system},
                                   {"role": "user", "content": "What's the weather tomorrow?"}], tools=tools))
    assert pieces == [ToolCall("get_weather", {"day": "tomorrow"}, "call_1")]
    warm, question = _ToolOllama.requests[0], _ToolOllama.requests[-1]
    assert json.dumps(warm["tools"]) == json.dumps(question["tools"])   # the same bytes: the cache holds
    assert question["think"] is False and warm["messages"][0] == question["messages"][0]


def test_a_model_without_tools_is_asked_again_without_them(tool_ollama):
    llm = OllamaLLM("gemma3:4b", host=tool_ollama)
    pieces = list(llm.stream_chat([{"role": "user", "content": "Hello"}], tools=default_registry().ollama_tools()))
    assert pieces == ["Sunny. "] and llm.supports_tools is False
    assert ["tools" in r for r in _ToolOllama.requests] == [True, False]
    list(llm.stream_chat([{"role": "user", "content": "Hello"}], tools=default_registry().ollama_tools()))
    assert "tools" not in _ToolOllama.requests[-1]                       # not offered again


# ---------------------------------------------------------------- the weather tool (fake HTTP)

GEO = {"results": [{"name": "Nice", "latitude": 43.70313, "longitude": 7.26608, "country": "France",
                    "country_code": "FR", "admin1": "Provence-Alpes-Côte d'Azur"}]}
FORECAST = {"current": {"time": "2026-09-28T14:15", "temperature_2m": 21.4, "apparent_temperature": 20.6,
                        "weather_code": 2, "wind_speed_10m": 12.3, "precipitation": 0.0},
            "daily": {"time": ["2026-09-28", "2026-09-29"], "weather_code": [2, 61],
                      "temperature_2m_max": [24.2, 19.6], "temperature_2m_min": [17.1, 15.4],
                      "precipitation_probability_max": [10, 80]}}


class FakeHTTP:
    def __init__(self, geo=GEO, forecast=FORECAST, fail: Exception | None = None, delay: float = 0.0):
        self.geo, self.forecast, self.fail, self.delay = geo, forecast, fail, delay
        self.requests: list[tuple[str, dict, float]] = []

    def __call__(self, url, params, timeout):
        self.requests.append((url, dict(params), timeout))
        if self.delay:
            time.sleep(self.delay)
        if self.fail is not None:
            raise self.fail
        return self.geo if url == GEOCODING_URL else self.forecast


def test_weather_now_today_and_tomorrow():
    http = FakeHTTP()
    now = [0.0]
    w = OpenMeteoWeather("Nice", fetch=http, clock=lambda: now[0])
    cur = w(context={"language": "fr"})
    assert cur == {"place": "Nice, France", "when": "now (local time 14:15)", "conditions": "partly cloudy",
                   "temperature_c": 21, "feels_like_c": 21, "wind_kmh": 12, "precipitation_mm": 0.0,
                   "today_min_c": 17, "today_max_c": 24, "today_rain_chance_percent": 10}
    geo, fc = http.requests
    assert geo[0] == GEOCODING_URL and geo[1]["name"] == "Nice" and geo[1]["language"] == "fr" and geo[1]["count"] == 1
    assert fc[0] == FORECAST_URL and fc[1]["latitude"] == 43.703 and fc[1]["forecast_days"] == 2
    assert "weather_code" in fc[1]["current"] and "precipitation_probability_max" in fc[1]["daily"]
    assert 0 < fc[2] <= 4.0                                           # what is left of the 4 s budget
    tomorrow = w(day="tomorrow")
    assert tomorrow == {"place": "Nice, France", "when": "tomorrow, Tuesday 29 September", "conditions": "light rain",
                        "min_c": 15, "max_c": 20, "rain_chance_percent": 80}
    assert w(day="today")["conditions"] == "partly cloudy"
    assert len(http.requests) == 2                                    # cached: place and forecast
    now[0] = 601.0
    w(day="now")
    assert len(http.requests) == 3                                    # the forecast expired, the place did not


def test_weather_place_choice_and_errors():
    http = FakeHTTP(geo={"results": [{"name": "Paris", "latitude": 33.6, "longitude": -95.5, "country": "United States"},
                                     {"name": "Paris", "latitude": 48.85, "longitude": 2.35, "country": "France"}]})
    w = OpenMeteoWeather(fetch=http)
    assert w(place="Paris, France")["place"] == "Paris, France"
    assert http.requests[0][1]["name"] == "Paris" and http.requests[0][1]["count"] == 5
    with pytest.raises(ToolError, match="ask the person which city"):
        OpenMeteoWeather(fetch=FakeHTTP())()
    missing = FakeHTTP(geo={"generationtime_ms": 0.1})
    w = OpenMeteoWeather(fetch=missing)
    for _ in range(2):
        with pytest.raises(ToolError, match="no place called 'Atlantis' was found"):
            w(place="Atlantis")
    assert len(missing.requests) == 1                                 # not found is remembered too
    with pytest.raises(ToolError, match="did not answer in time"):
        OpenMeteoWeather("Nice", fetch=FakeHTTP(fail=TimeoutError("timed out")))()
    import urllib.error
    with pytest.raises(ToolError, match="could not be reached"):
        OpenMeteoWeather("Nice", fetch=FakeHTTP(fail=urllib.error.URLError("no route to host")))()
    with pytest.raises(ToolError, match="refused"):
        OpenMeteoWeather("Nice", fetch=FakeHTTP(forecast={"error": True, "reason": "bad latitude"}))()


def test_weather_tool_through_the_registry():
    reg = ToolRegistry([weather_tool("", fetch=FakeHTTP())])
    res = reg.call("get_weather", {"place": "Nice", "day": "TOMORROW"}, {"language": "en"})
    assert res.ok and json.loads(res.content)["conditions"] == "light rain"
    res = reg.call("get_weather", {})
    assert not res.ok and "which city" in res.error
    slow = ToolRegistry([Tool("get_weather", "x", weather_tool().parameters,
                              OpenMeteoWeather("Nice", fetch=FakeHTTP(delay=1.0)), timeout=0.2,
                              online=True, wants_context=True)])
    res = slow.call("get_weather", {})
    assert not res.ok and "no answer within 0.2 s" in res.error
    tool = weather_tool()
    assert tool.online and tool.says_filler and tool.timeout <= 6


# ---------------------------------------------------------------- settings and the app

def test_tool_settings_validation_and_defaults(tmp_path):
    assert validate({"tools": False, "internet": True, "home_place": "  Saint-Laurent-du-Var  "}) == {
        "tools": False, "internet": True, "home_place": "Saint-Laurent-du-Var"}
    assert validate({"home_place": None}) == {"home_place": ""}
    for bad in [{"tools": "yes"}, {"internet": 1}, {"home_place": "x" * 81}, {"home_place": 3},
                {"home_place": "Ni\x07ce"}, {"home_place": '{"a": 1}'}]:
        with pytest.raises(ValueError):
            validate(bad)
    ctl = VoiceController(check=None, path=tmp_path / "voice.json")
    s = ctl.app_settings()
    assert s["tools"] is True and s["internet"] is True and s["home_place"] == ""
    ctl.update_settings({"internet": False, "home_place": "Nice"})
    assert json.loads((tmp_path / "voice.json").read_text())["home_place"] == "Nice"
    config = voice_cli._config(None, settings=ctl.settings())
    assert config.internet is False and config.home_place == "Nice" and config.tools is True


def test_options_list_the_tools():
    from marvin_host.voice.control import options
    tools = options({"ollama_host": "http://127.0.0.1:9"})["tools"]
    assert [t["name"] for t in tools] == ["get_weather"] and tools[0]["online"] is True


def test_reply_entries_carry_the_tool_calls(tmp_path):
    ctl = VoiceController(check=None, path=tmp_path / "voice.json")
    call = {"name": "get_weather", "arguments": {"day": "now"}, "ok": True, "seconds": 0.4, "result": "{}"}
    ctl._on_voice("reply", {"t": time.time(), "text": "Il fait beau.", "latency": {"tools": 0.4},
                            "tools": [call], "context": "Context:", "prompt": "p", "model": "m"})
    entry = ctl.recent()[-1]
    assert entry["tools"] == [call] and entry["latency"]["tools"] == 0.4


def test_demo_weather_reply_has_a_fake_tool_call():
    from marvin_host.ui import demo
    assert demo.is_weather_question("What's the weather like?") and demo.is_weather_question("Quelle météo demain ?")
    assert not demo.is_weather_question("What time is it?")
    call = demo.WEATHER_CALL
    assert call["name"] == "get_weather" and "demo" in call["result"] and "demo" in demo.WEATHER_ANSWER
    assert {"tools", "llm_first_token_2"} <= set(demo.WEATHER_LATENCY)

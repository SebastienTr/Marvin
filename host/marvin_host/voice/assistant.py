"""The voice assistant: hears "Marvin, ...", thinks with a local model, answers aloud.

    source -> [capture thread: echo gate] -> VAD segmenter -> wake word / Whisper -> question
           -> LLM (streamed) -> first clause / sentences -> [speaker thread: TTS -> sink]

The audio machine (listening, wake word, echo gate, listening windows, speaking, barge-in) is
`VoiceEngine` (engine.py), shared with the voice sidecar; this module adds the conversation that
runs in process: the language model, its tools, the persona and context, and the history. See
engine.py for the states, the controls (`ask`, `listen_now`, `mute`, `stop_speaking`) and the
events (`add_listener`).

Tools (voice/tools, docs/voice.md): the model is offered the tools switched on in the settings, the
same list with every request. When it calls some, nothing more of that response is spoken; an
online tool gets a short filler ("Let me check…") while it runs, the calls and their results are
added to the conversation, and the model is asked again; what it says then is spoken as usual. At
most `max_tool_rounds` rounds, then it must answer. The whole exchange goes into the history.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import copy
import logging
import re
import time
from typing import Callable

from ..audio import AudioSink, AudioSource
from . import persona
from .engine import (Status, VoiceConfig, VoiceEngine, _Filler, _Job, chime, envelope,  # noqa: F401 - re-exported
                     format_latency)
from .llm import LLM, LLMUnavailable, ToolCall
from .stt import STT
from .text import SentenceSplitter, clean_for_speech, looks_like_payload, payload_tool_calls, strip_thinking
from .tools import ToolRegistry
from .tts import TTS
from .vad import Vad
from .wake import WakeWordDetector

log = logging.getLogger("marvin.voice")

THINK_TAG = "</think>"
THINK_END = re.compile(r"</think\b\s*>?")


class VoiceAssistant(VoiceEngine):
    """See the module docstring. Components not passed in are built from `config`."""

    def __init__(self, source: AudioSource, sink: AudioSink, brain=None, config: VoiceConfig | None = None, *,
                 stt: STT | None = None, llm: LLM | None = None, tts: TTS | None = None,
                 vad: Vad | None = None, wake: WakeWordDetector | None = None,
                 tools: ToolRegistry | None = None,
                 on_status: Callable[[str], None] | None = None,
                 on_transcript: Callable[[str], None] | None = None,
                 on_reply: Callable[[str], None] | None = None):
        c = config or VoiceConfig()
        self.brain = brain
        language = c.language or c.default_language
        if stt is None:
            from .stt import make_stt
            stt = make_stt(c.stt, c.stt_model, (c.language,) if c.language else c.languages)
        if tools is None:
            from .tools import default_registry
            tools = default_registry(enabled=c.tools, internet=c.internet, home_place=c.home_place)
        self.tools = tools
        if llm is None:
            from .llm import OllamaLLM
            llm = OllamaLLM(c.llm_model, c.ollama_host)
            if llm.available():     # rehearse a real first question: model loaded, prompt (and tools) cached
                schemas = tools.ollama_tools()
                llm.warm_up(persona.persona_prompt(language, tools=bool(schemas)),
                            persona.user_message("Bonjour."), tools=schemas)
                if schemas and not llm.supports_tools:      # found out during the rehearsal: again, without
                    llm.warm_up(persona.persona_prompt(language), persona.user_message("Bonjour."))
        self.llm = llm
        self.history: list[dict] = []
        self._last_turn = 0.0                                # monotonic
        self._remembered_uid = 0                             # the question the history ends with
        super().__init__(source, sink, c, stt=stt, tts=tts, vad=vad, wake=wake,
                         on_status=on_status, on_transcript=on_transcript, on_reply=on_reply)

    def _tool_schemas(self) -> list[dict] | None:
        """Ollama's tools list: the same object for every request while the settings stay."""
        if self.tools is None or not getattr(self.llm, "supports_tools", True):
            return None
        return self.tools.ollama_tools()

    def _stream(self, messages: list[dict], schemas: list[dict] | None):
        return self.llm.stream_chat(messages, tools=schemas) if schemas else self.llm.stream_chat(messages)

    def _messages(self, job: _Job, schemas: list[dict] | None = None) -> tuple[list[dict], str]:
        state, events = None, ()
        if self.brain is not None:
            state = copy.copy(self.brain.state)
            try:
                events = list(getattr(self.brain, "events", ()))
            except RuntimeError:            # the brain appended an event meanwhile: skip them this time
                events = ()
        now = time.monotonic()
        if self.history and now - self._last_turn > self.config.memory_reset_s:
            log.debug("conversation forgotten after %.0f s of silence", now - self._last_turn)
            self.history.clear()
        if job.continues and self._remembered_uid in job.continues:
            # its answer was cut to go on: this question says it all again, the cut turn goes
            starts = [i for i, m in enumerate(self.history) if m["role"] == "user"]
            if starts:
                del self.history[starts[-1]:]
            self._remembered_uid = 0
        home = self.config.home_place.strip() if schemas else ""
        job.context = persona.context_block(state, events, home=home)
        user = persona.user_message(job.text, language=job.language, context=job.context)
        return [{"role": "system", "content": persona.persona_prompt(job.language, tools=bool(schemas))},
                *self.history,
                {"role": "user", "content": user}], user

    def _answer(self, job: _Job) -> None:
        schemas = self._tool_schemas()
        messages, user = self._messages(job, schemas)
        lat = self.last_latency
        t = time.monotonic()
        failure: list[str] = []
        hint: list[str] = []
        exchange: list[dict] = []           # tool calls and results: sent again, then kept in the history
        calls: list[dict] = []              # the same, as the app shows them
        answer: list[str] = []              # what the model said (a filler aside)

        def produce(emit):
            def say(s: str) -> None:
                lat.setdefault("first_chunk", time.monotonic() - t)
                answer.append(s)
                emit(s)

            filler = False
            rounds = 0
            try:
                while True:
                    splitter = SentenceSplitter()
                    t_req = time.monotonic()
                    first = True
                    text: list[str] = []
                    # held back until the response is complete: it looks like a tool call written as
                    # text, or it answers a tool result (models may think aloud there, see strip_thinking)
                    held: bool | None = True if rounds else None
                    tool_calls: list[ToolCall] = []
                    fed, scan, cut, said_before = 0, 0, False, len(answer)
                    for piece in self._stream(messages + exchange, schemas):
                        if job.cancel.is_set():
                            return
                        if first:
                            first = False
                            if rounds == 0:
                                lat.setdefault("llm_first_token", time.monotonic() - t)
                            else:
                                lat["llm_first_token_2"] = lat.get("llm_first_token_2", 0.0) + time.monotonic() - t_req
                        if isinstance(piece, ToolCall):
                            tool_calls.append(piece)
                            continue
                        text.append(piece)
                        if held is None:                # decided on the first characters
                            so_far = "".join(text)
                            held = looks_like_payload(so_far)
                            if held is None:
                                continue
                            piece = so_far
                        if held:
                            continue
                        # Reasoning written into the answer (strip_thinking) while it streams: text,
                        # then "</think>", then the answer again. Hold back a possible start of the tag;
                        # at the tag, if something was said, finish its sentence and drop the repeat,
                        # else drop what came before it.
                        if cut:
                            continue
                        joined = "".join(text)
                        end = THINK_END.search(joined, max(scan, fed - len(THINK_TAG)))
                        if end is not None:
                            if len(answer) > said_before:
                                for s in splitter.feed(joined[fed:end.start()]):
                                    say(s)
                                for s in splitter.flush():
                                    say(s)
                                cut = True
                                continue
                            splitter = SentenceSplitter()
                            fed = scan = end.end()
                        stop = len(joined)
                        for k in range(1, len(THINK_TAG) + 1):
                            if joined.endswith(THINK_TAG[:k]):
                                stop = len(joined) - k
                        stop = max(stop, fed)
                        for s in splitter.feed(joined[fed:stop]):
                            say(s)
                        fed = stop
                    content = "".join(text)
                    if held is None and content.strip():    # too short to decide: treat it as text
                        held = True
                    if held:
                        tool_calls += [c for c in map(ToolCall.parse, payload_tool_calls(content)) if c is not None]
                    if tool_calls and self.tools is not None and rounds < self.config.max_tool_rounds:
                        # what was not spoken yet of this response is dropped (the splitter's rest)
                        if not filler and not answer and any(
                                (tl := self.tools.get(c.name)) is not None and tl.says_filler for c in tool_calls):
                            emit.filler(persona.phrase("checking", job.language))
                            filler = True
                        t_tools = time.monotonic()
                        results = []
                        for c in tool_calls:
                            results.append(self.tools.call(c.name, c.arguments, {"language": job.language}))
                            if job.cancel.is_set():
                                return
                        lat["tools"] = lat.get("tools", 0.0) + time.monotonic() - t_tools
                        exchange.append({"role": "assistant", "content": "" if held else content,
                                         "tool_calls": [c.message() for c in tool_calls]})
                        for c, res in zip(tool_calls, results):
                            # "tool_name" for Ollama (0.9 and later), "tool_call_id" when the call had an id
                            msg = {"role": "tool", "content": res.content, "tool_name": c.name}
                            if c.id:
                                msg["tool_call_id"] = c.id
                            exchange.append(msg)
                            calls.append(res.record())
                        rounds += 1
                        continue
                    if tool_calls:
                        log.warning("ignoring %d more tool call(s) after %d round(s)", len(tool_calls), rounds)
                    if held and not tool_calls:         # it was not a tool call after all: say what can be said
                        for s in splitter.feed(strip_thinking(content)):
                            say(s)
                    if not held and not cut and fed < len(content):   # a held-back "<" at the very end
                        for s in splitter.feed(content[fed:]):
                            say(s)
                    for s in splitter.flush():
                        say(s)
                    if not clean_for_speech(" ".join(answer)) and (rounds or held or tool_calls):
                        say(persona.phrase("no_answer", job.language))
                    return
            except LLMUnavailable as e:
                log.error("%s. %s", e, e.hint)
                failure.append("llm_down")
                hint.append(f"{e}. {e.hint}".strip())
                emit(persona.phrase("llm_down", job.language))
            except Exception:
                log.exception("the language model failed")
                failure.append("error")
                emit(persona.phrase("error", job.language))

        text = self._speak_all(job, produce, lat)
        said_answer = clean_for_speech(" ".join(answer))
        lat["total"] = time.monotonic() - job.t_heard

        def emit_reply(**kw):
            # what the model was given, for the app's "why did Marvin say that"
            extra = {"tools": calls} if calls else {}
            self._emit("reply", text=text, language=job.language, latency=dict(lat), proactive=False,
                       context=job.context, prompt=user, model=self.model_name, **extra,
                       **{"interrupted": False, "error": None, "hint": "", **kw})

        if failure:
            emit_reply(error=failure[0], hint=hint[0] if hint else "")
            return
        if job.cancel.is_set():
            log.info("interrupted (%s)", job.cancel_reason or "stop")
            if job.cancel_reason == "merged":   # the question went on: the joined one is answered instead
                if job.audible:
                    emit_reply(interrupted=True)
                return
            if said_answer or exchange:
                self._remember(user, (said_answer + " …").strip(), exchange, uid=job.uid)
            emit_reply(interrupted=True)
            return
        log.info("said: %s", text)
        self._last_reply = text
        log.info("latency: %s", format_latency(lat))
        self._remember(user, said_answer, exchange, uid=job.uid)
        emit_reply()
        if self.on_reply:
            try:
                self.on_reply(text)
            except Exception:
                log.exception("on_reply failed")

    @property
    def model_name(self) -> str:
        """The language model's name, as the app shows it."""
        return str(getattr(self.llm, "model", None) or self.config.llm_model)

    def _remember(self, question: str, answer: str, exchange: list[dict] | tuple = (), uid: int = 0) -> None:
        """Keeps the conversation for the next question: the question, the tool calls and results
        in between (`exchange`), and the answer. The model server reuses its work on everything up
        to the first message that changed, so the history only ever grows at the end: dropping the
        oldest turn every time would change the start of it at each question and make the model read
        the whole conversation again (10 s and more with a large model). When it is full, the older
        half of the turns goes at once, so that happens once every few questions. A turn is
        everything from a question to the next one: a tool exchange is never cut in two."""
        self.history += [{"role": "user", "content": question}, *exchange, {"role": "assistant", "content": answer}]
        starts = [i for i, m in enumerate(self.history) if m["role"] == "user"]
        if len(starts) > self.config.memory_turns:
            keep = max(1, self.config.memory_turns // 2)
            del self.history[:starts[-keep]]
        self._last_turn = time.monotonic()
        self._remembered_uid = uid

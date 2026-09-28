"""The language model: a local Ollama server, streamed.

`OllamaLLM` talks to Ollama's HTTP API (`POST /api/chat`, newline-delimited JSON when streaming)
with the standard library only. Nothing leaves the computer.

Default model: `qwen3:4b-instruct` (Qwen3 4B Instruct 2507, 2.5 GB, Q4_K_M). It is the non-thinking
variant (no hidden reasoning to wait for before the first word), it speaks French well, and it
answers in about a second on an Apple Silicon Mac. Thinking is turned off (`think: false`), so
thinking models such as `qwen3:4b` or `qwen3.5:4b` also answer at once. Lighter: `qwen3:1.7b` or
`llama3.2:3b`; heavier and better: `qwen3:8b`, `gemma3:4b`, `mistral-small3.2` (24B, needs 16 GB
of free memory).

Tools (voice/tools): `stream_chat(messages, tools=...)` passes Ollama a `tools` list; when the model
decides to call one, the stream yields `ToolCall` items (Ollama sends `message.tool_calls`, usually
in one chunk with empty content) among the text pieces. A model that cannot use tools (Ollama
answers "does not support tools") is asked again without them, and `supports_tools` turns False.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from typing import Callable, Iterable, Iterator, NamedTuple, Protocol, Union

log = logging.getLogger("marvin.voice.llm")

DEFAULT_MODEL = "qwen3:4b-instruct"
DEFAULT_HOST = "http://localhost:11434"

Message = dict          # {"role": "system" | "user" | "assistant" | "tool", "content": str, ...}


class ToolCall(NamedTuple):
    """The model asks for a tool: its name and arguments (a dict; a JSON string from some models
    is parsed; what cannot be parsed is kept as the string). ``id``: when the server gives one."""
    name: str
    arguments: dict | str
    id: str | None = None

    def message(self) -> dict:
        """As Ollama expects it in the assistant message's ``tool_calls``."""
        call = {"function": {"name": self.name,
                             "arguments": self.arguments if isinstance(self.arguments, dict) else {}}}
        if self.id:
            call["id"] = self.id
        return call

    @classmethod
    def parse(cls, raw) -> "ToolCall | None":
        """From Ollama's (or OpenAI's) ``{"function": {"name", "arguments"}}``, or a bare
        ``{"name", "arguments" | "parameters"}``. None if it has no name."""
        if not isinstance(raw, dict):
            return None
        fn = raw.get("function") if isinstance(raw.get("function"), dict) else raw
        name = fn.get("name")
        if not isinstance(name, str) or not name:
            return None
        args = fn.get("arguments", fn.get("parameters", {}))
        if isinstance(args, str):
            try:
                args = json.loads(args) if args.strip() else {}
            except ValueError:
                pass
        if args is None:
            args = {}
        return cls(name, args, raw.get("id") if isinstance(raw.get("id"), str) else None)


Piece = Union[str, ToolCall]


class LLMUnavailable(RuntimeError):
    """The model cannot be reached (server down, model not pulled). `hint` says how to fix it."""

    def __init__(self, message: str, hint: str = ""):
        super().__init__(message)
        self.hint = hint


class ToolsUnsupported(LLMUnavailable):
    """The model cannot use tools (Ollama: "... does not support tools")."""


class LLM(Protocol):
    def stream_chat(self, messages: list[Message], tools: list[dict] | None = None) -> Iterator[Piece]:
        """Yields the reply in pieces as it is generated (text, and `ToolCall` when the model calls
        a tool from `tools`). Raises LLMUnavailable."""
        ...


class OllamaLLM:
    def __init__(self, model: str = DEFAULT_MODEL, host: str = DEFAULT_HOST, temperature: float = 0.6,
                 num_predict: int = 200, keep_alive: str = "30m", think: bool | None = False,
                 timeout: float = 60.0, num_ctx: int = 8192):
        self.model = model
        self.host = host.rstrip("/")
        # num_ctx is set explicitly so that it never differs between requests (a change reloads the model)
        self.options = {"temperature": temperature, "num_predict": num_predict, "num_ctx": num_ctx}
        self.keep_alive = keep_alive        # keep the model in memory between questions
        # False: no hidden reasoning before the answer on thinking models (qwen3, qwen3.5: 8 s -> 0.2 s
        # to the first word); accepted by non-thinking models. None: the model's default.
        self.think = think
        self.timeout = timeout
        self.supports_tools = True          # False once Ollama said the model cannot use tools

    def _hint(self) -> str:
        return (f"Install Ollama (https://ollama.com/download or `brew install ollama`), start it "
                f"(`ollama serve` or the Ollama app), then `ollama pull {self.model}`.")

    def _request(self, path: str, body: dict | None = None, timeout: float | None = None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.host + path, data=data, method="GET" if body is None else "POST",
                                     headers={"Content-Type": "application/json"})
        try:
            return urllib.request.urlopen(req, timeout=timeout or self.timeout)
        except urllib.error.HTTPError as e:
            try:
                msg = json.loads(e.read().decode() or "{}").get("error", "")
            except (ValueError, OSError):
                msg = ""
            if e.code == 400 and "does not support tools" in msg:
                raise ToolsUnsupported(f"{self.model} cannot use tools: {msg}") from e
            if e.code == 404:
                raise LLMUnavailable(f"Ollama has no model {self.model!r}: {msg or e}",
                                     f"Run `ollama pull {self.model}`.") from e
            raise LLMUnavailable(f"Ollama error {e.code}: {msg or e.reason}", self._hint()) from e
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as e:
            raise LLMUnavailable(f"cannot reach Ollama at {self.host}: {getattr(e, 'reason', e)}", self._hint()) from e

    def available(self) -> bool:
        """True if the server answers and has the model. Logs what is missing otherwise."""
        try:
            with self._request("/api/tags", timeout=3) as r:
                names = {m.get("name", "") for m in json.load(r).get("models", [])}
        except LLMUnavailable as e:
            log.warning("%s. %s", e, e.hint)
            return False
        if self.model not in names and f"{self.model}:latest" not in names:
            log.warning("Ollama is running but has no model %r. Run `ollama pull %s`.", self.model, self.model)
            return False
        return True

    def _chat_body(self, messages: list[Message], tools: list[dict] | None = None) -> dict:
        """The request body. Warm-up and questions use exactly the same one (options, keep_alive,
        think, stream, tools): any difference can make Ollama reload the model or reprocess the
        prompt (the tool definitions are rendered into the prompt, next to the system message)."""
        body = {"model": self.model, "messages": messages, "stream": True,
                "options": dict(self.options), "keep_alive": self.keep_alive}
        if self.think is not None:
            body["think"] = self.think
        if tools and self.supports_tools:
            body["tools"] = tools
        return body

    def first_token(self, messages: list[Message], timeout: float = 300.0, tools: list[dict] | None = None) -> float:
        """Sends a real request, stops at the first token, returns how long it took."""
        t = time.monotonic()
        for _ in self.stream_chat(messages, timeout=timeout, tools=tools):
            break
        return time.monotonic() - t

    def warm_up(self, system: str | None = None, user: str = "Bonjour.", tools: list[dict] | None = None) -> float | None:
        """Loads the model and fills Ollama's prompt cache before the first question, by rehearsing
        a real one: the same system prompt, the same tools, a user message of the same shape, the
        same options. A second rehearsal checks that the cached prompt is reused (it then answers
        at once). Returns the time it took, None if Ollama is unreachable or has no such model."""
        messages = [{"role": "system", "content": system}] if system else []
        messages.append({"role": "user", "content": user})
        t = time.monotonic()
        try:
            self.first_token(messages, tools=tools)
            loaded = time.monotonic() - t
            again = self.first_token(messages, tools=tools)
        except LLMUnavailable as e:
            log.warning("%s. %s", e, e.hint)
            return None
        log.info("model %s ready (prompt cached) in %.1f s; first token now %.2f s", self.model, loaded, again)
        if again > 1.5 and again > 0.5 * loaded:
            log.warning("Ollama did not reuse the cached prompt (first token still %.1f s): the first "
                        "question will be slow", again)
        return loaded

    def stream_chat(self, messages: list[Message], timeout: float | None = None,
                    tools: list[dict] | None = None) -> Iterator[Piece]:
        """`timeout`: for the first byte (loading a big model takes a while) and between pieces.
        Yields text pieces, and a `ToolCall` for each tool the model calls."""
        body = self._chat_body(messages, tools)
        t = time.monotonic()
        first = True
        try:
            r = self._request("/api/chat", body, timeout=timeout)
        except ToolsUnsupported as e:
            log.warning("%s: answering without tools", e)
            self.supports_tools = False
            r = self._request("/api/chat", self._chat_body(messages), timeout=timeout)
        with r:
            for line in r:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                if msg.get("error"):
                    raise LLMUnavailable(f"Ollama error: {msg['error']}", self._hint())
                message = msg.get("message") or {}
                piece = message.get("content", "")
                for raw in message.get("tool_calls") or ():
                    call = ToolCall.parse(raw)
                    if call is not None:
                        yield call
                if piece:
                    if first:
                        log.debug("first token after %.2f s", time.monotonic() - t)
                        first = False
                    yield piece
                if msg.get("done"):
                    break


class FakeLLM:
    """For tests: replies from a list (one per call) or from `fn(messages)`, streamed in small pieces.

    A reply is a text, a `ToolCall`, or a list mixing both (in the order they are streamed), so a
    test can script tool calls: ``FakeLLM([ToolCall("get_weather", {"day": "now"}), "Il fait beau."])``
    calls the tool in the first request and answers in the second. `tools` records the tools list
    each request was given."""

    def __init__(self, replies: Iterable | Callable[[list[Message]], object] = ("D'accord.",),
                 piece: int = 7, fail: bool = False):
        self._fn = replies if callable(replies) else None
        self._replies = [] if callable(replies) else list(replies)
        self.piece = piece
        self.fail = fail
        self.calls: list[list[Message]] = []
        self.tools: list[list[dict] | None] = []

    def stream_chat(self, messages: list[Message], tools: list[dict] | None = None) -> Iterator[Piece]:
        self.calls.append([dict(m) for m in messages])
        self.tools.append(tools)
        if self.fail:
            raise LLMUnavailable("fake LLM is down", "Start the fake LLM.")
        reply = self._fn(messages) if self._fn else (self._replies.pop(0) if self._replies else "")
        for part in (reply if isinstance(reply, list) else [reply]):
            if isinstance(part, ToolCall):
                yield part
                continue
            for i in range(0, len(part), self.piece):
                yield part[i:i + self.piece]

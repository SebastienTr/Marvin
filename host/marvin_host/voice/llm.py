"""The language model: a local Ollama server, streamed.

`OllamaLLM` talks to Ollama's HTTP API (`POST /api/chat`, newline-delimited JSON when streaming)
with the standard library only. Nothing leaves the computer.

Default model: `qwen3:4b-instruct` (Qwen3 4B Instruct 2507, 2.5 GB, Q4_K_M). It is the non-thinking
variant (no hidden reasoning to wait for before the first word), it speaks French well, and it
answers in about a second on an Apple Silicon Mac. Thinking is turned off (`think: false`), so
thinking models such as `qwen3:4b` or `qwen3.5:4b` also answer at once. Lighter: `qwen3:1.7b` or
`llama3.2:3b`; heavier and better: `qwen3:8b`, `gemma3:4b`, `mistral-small3.2` (24B, needs 16 GB
of free memory).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from typing import Callable, Iterable, Iterator, Protocol

log = logging.getLogger("marvin.voice.llm")

DEFAULT_MODEL = "qwen3:4b-instruct"
DEFAULT_HOST = "http://localhost:11434"

Message = dict          # {"role": "system" | "user" | "assistant", "content": str}


class LLMUnavailable(RuntimeError):
    """The model cannot be reached (server down, model not pulled). `hint` says how to fix it."""

    def __init__(self, message: str, hint: str = ""):
        super().__init__(message)
        self.hint = hint


class LLM(Protocol):
    def stream_chat(self, messages: list[Message]) -> Iterator[str]:
        """Yields the reply in pieces as it is generated. Raises LLMUnavailable."""
        ...


class OllamaLLM:
    def __init__(self, model: str = DEFAULT_MODEL, host: str = DEFAULT_HOST, temperature: float = 0.6,
                 num_predict: int = 200, keep_alive: str = "30m", think: bool | None = False,
                 timeout: float = 60.0):
        self.model = model
        self.host = host.rstrip("/")
        self.options = {"temperature": temperature, "num_predict": num_predict}
        self.keep_alive = keep_alive        # keep the model in memory between questions
        # False: no hidden reasoning before the answer on thinking models (qwen3, qwen3.5: 8 s -> 0.2 s
        # to the first word); accepted by non-thinking models. None: the model's default.
        self.think = think
        self.timeout = timeout

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

    def warm_up(self) -> None:
        """Loads the model into memory now rather than at the first question."""
        try:
            with self._request("/api/chat", {"model": self.model, "messages": [], "keep_alive": self.keep_alive}):
                pass
        except LLMUnavailable as e:
            log.warning("%s. %s", e, e.hint)

    def stream_chat(self, messages: list[Message]) -> Iterator[str]:
        body = {"model": self.model, "messages": messages, "stream": True,
                "options": self.options, "keep_alive": self.keep_alive}
        if self.think is not None:
            body["think"] = self.think
        t = time.monotonic()
        first = True
        with self._request("/api/chat", body) as r:
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
                piece = msg.get("message", {}).get("content", "")
                if piece:
                    if first:
                        log.debug("first token after %.2f s", time.monotonic() - t)
                        first = False
                    yield piece
                if msg.get("done"):
                    break


class FakeLLM:
    """For tests: replies from a list (one per call) or from `fn(messages)`, streamed in small pieces."""

    def __init__(self, replies: Iterable[str] | Callable[[list[Message]], str] = ("D'accord.",),
                 piece: int = 7, fail: bool = False):
        self._fn = replies if callable(replies) else None
        self._replies = [] if callable(replies) else list(replies)
        self.piece = piece
        self.fail = fail
        self.calls: list[list[Message]] = []

    def stream_chat(self, messages: list[Message]) -> Iterator[str]:
        self.calls.append([dict(m) for m in messages])
        if self.fail:
            raise LLMUnavailable("fake LLM is down", "Start the fake LLM.")
        text = self._fn(messages) if self._fn else (self._replies.pop(0) if self._replies else "")
        for i in range(0, len(text), self.piece):
            yield text[i:i + self.piece]

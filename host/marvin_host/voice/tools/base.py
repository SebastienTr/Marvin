"""Tools the language model may call: their definition, the registry that offers them to Ollama,
and the safe way to run them.

A `Tool` is a name, a description, a JSON schema for its arguments and a Python function. The
`ToolRegistry` turns the tools that are switched on into Ollama's `tools` list (OpenAI-style
function schemas), always the same bytes for the same settings: the tool definitions are part of
the prompt the model server caches, so they must never change from one request to the next.

Running a call never raises: unknown tools, bad arguments, exceptions and timeouts all become an
error result the model can talk about ("I couldn't reach the weather service"). Every call is
recorded (name, arguments, result, duration, error) for the app's reply inspector.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import logging
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Callable, Iterable

log = logging.getLogger("marvin.voice.tools")

SUMMARY_CHARS = 280         # of a result, as recorded for the app


class ToolError(Exception):
    """Raised by a tool for an expected failure; the message is given to the model as is, so
    write it for the model: "no place called 'Nicee' was found"."""


@dataclass(frozen=True)
class Tool:
    """One tool.

    - ``name``: what the model calls (letters, digits, underscores);
    - ``description``: when to use it, for the model (one or two sentences);
    - ``parameters``: a JSON schema object ({"type": "object", "properties": ..., "required": [...]});
    - ``fn``: called with the validated arguments as keyword arguments (plus ``context=`` when
      ``wants_context``: {"language": "fr" | "en" ...}); returns a short text or anything JSON can
      encode (a small dict is best: the model reads it);
    - ``timeout``: seconds before the call is given up (the model is told it timed out);
    - ``online``: it needs the internet (switched off with the Internet setting);
    - ``filler``: say a short "let me check" while it runs; None means "when online".
    """
    name: str
    description: str
    parameters: dict
    fn: Callable[..., Any]
    timeout: float = 3.0
    online: bool = False
    wants_context: bool = False
    filler: bool | None = None

    @property
    def says_filler(self) -> bool:
        return self.online if self.filler is None else self.filler

    def schema(self) -> dict:
        return {"type": "function", "function": {"name": self.name, "description": self.description,
                                                 "parameters": self.parameters}}


@dataclass
class ToolResult:
    """One call, done. ``content`` is what the model reads; ``record()`` what the app shows."""
    name: str
    arguments: dict
    ok: bool
    content: str
    seconds: float
    error: str = ""
    extra: dict = field(default_factory=dict)

    def record(self) -> dict:
        out = {"name": self.name, "arguments": self.arguments, "ok": self.ok,
               "seconds": round(self.seconds, 3)}
        if self.ok:
            out["result"] = _short(self.content)
        else:
            out["error"] = self.error
        return out


def _short(text: str, n: int = SUMMARY_CHARS) -> str:
    return text if len(text) <= n else text[: n - 1].rstrip() + "…"


def _canonical(obj):
    """The same structure with every dict's keys sorted: the same bytes whatever order the tool's
    author wrote its schema in."""
    return json.loads(json.dumps(obj, sort_keys=True, ensure_ascii=False))


# ---------------------------------------------------------------- argument validation

_TYPES = {"string": str, "boolean": bool, "object": dict, "array": list}


def _check_type(key: str, spec: dict, v):
    kind = spec.get("type")
    if kind in ("integer", "number"):
        if isinstance(v, str):                   # small models often quote numbers
            try:
                v = float(v.strip())
            except ValueError:
                raise ToolError(f"argument '{key}' must be a number") from None
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            raise ToolError(f"argument '{key}' must be a number")
        if kind == "integer":
            if float(v) != int(v):
                raise ToolError(f"argument '{key}' must be a whole number")
            v = int(v)
    elif kind in _TYPES:
        if not isinstance(v, _TYPES[kind]) or (kind != "boolean" and isinstance(v, bool)):
            raise ToolError(f"argument '{key}' must be a {kind}")
    if "enum" in spec:
        choices = spec["enum"]
        if isinstance(v, str):
            match = [c for c in choices if isinstance(c, str) and c.lower() == v.strip().lower()]
            if match:
                v = match[0]
        if v not in choices:
            raise ToolError(f"argument '{key}' must be one of {', '.join(map(str, choices))}")
    if isinstance(v, str) and "maxLength" in spec and len(v) > spec["maxLength"]:
        raise ToolError(f"argument '{key}' is too long (at most {spec['maxLength']} characters)")
    return v


def validate_arguments(schema: dict, arguments) -> dict:
    """The arguments checked against ``schema``'s required keys, types and enums. Raises ToolError.
    Arguments given as a JSON string are parsed, null values and unknown keys are dropped (small
    models add them), quoted numbers are accepted, enum strings are matched without case."""
    if arguments is None or arguments == "":
        arguments = {}
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments)
        except ValueError:
            raise ToolError("the arguments are not valid JSON") from None
    if not isinstance(arguments, dict):
        raise ToolError("the arguments must be a JSON object")
    props = schema.get("properties", {})
    out = {}
    for key, v in arguments.items():
        if v is None or key not in props:
            continue
        out[key] = _check_type(key, props[key], v)
    missing = [k for k in schema.get("required", ()) if k not in out]
    if missing:
        raise ToolError(f"missing argument{'s' if len(missing) > 1 else ''}: {', '.join(missing)}")
    return out


# ---------------------------------------------------------------- the registry

class ToolRegistry:
    """The tools Marvin has, and which of them the model is offered.

    ``enabled`` False: no tools at all. ``internet`` False: tools marked ``online`` are not
    offered (Marvin fully offline). A tool that is not offered does not exist for the model: it is
    not in the request, and a call to it is answered as an unknown tool.
    """

    def __init__(self, tools: Iterable[Tool] = (), enabled: bool = True, internet: bool = True,
                 history: int = 50):
        self._tools: dict[str, Tool] = {}
        self.enabled = enabled
        self.internet = internet
        self.calls: deque[dict] = deque(maxlen=history)     # recent calls, as recorded
        self._schemas: tuple | None = None
        self._lock = threading.Lock()
        for t in tools:
            self.add(t)

    def add(self, tool: Tool) -> None:
        if not tool.name.replace("_", "").isalnum():
            raise ValueError(f"tool names are letters, digits and underscores: {tool.name!r}")
        if tool.parameters.get("type") != "object":
            raise ValueError(f"{tool.name}: parameters must be a JSON schema of type object")
        with self._lock:
            self._tools[tool.name] = tool
            self._schemas = None

    def all(self) -> list[Tool]:
        """Every tool, offered or not, by name."""
        return [self._tools[n] for n in sorted(self._tools)]

    def is_offered(self, tool: Tool) -> bool:
        return self.enabled and (self.internet or not tool.online)

    def offered(self) -> list[Tool]:
        """The tools the model is given, by name (a fixed order: the request never changes)."""
        return [t for t in self.all() if self.is_offered(t)]

    def get(self, name: str) -> Tool | None:
        t = self._tools.get(name)
        return t if t is not None and self.is_offered(t) else None

    def ollama_tools(self) -> list[dict] | None:
        """Ollama's ``tools`` list for the offered tools, or None when there are none. The same
        object (and so the same JSON bytes) every time for the same settings: do not modify it."""
        key = (self.enabled, self.internet)
        with self._lock:
            if self._schemas is None or self._schemas[0] != key:
                offered = self.offered()
                self._schemas = (key, _canonical([t.schema() for t in offered]) if offered else None)
            return self._schemas[1]

    def status(self) -> list[dict]:
        """Every tool and whether it is offered, for the app."""
        return [{"name": t.name, "description": t.description, "online": t.online, "on": self.is_offered(t)}
                for t in self.all()]

    def call(self, name: str, arguments=None, context: dict | None = None) -> ToolResult:
        """Runs one call; never raises. Unknown or switched-off tools, bad arguments, exceptions and
        timeouts give an error result (``ok`` False) whose ``content`` tells the model what went
        wrong."""
        t0 = time.monotonic()
        shown = arguments if isinstance(arguments, dict) else ({} if arguments in (None, "") else
                                                                 {"raw": str(arguments)[:200]})
        tool = self.get(name)
        if tool is None:
            names = [t.name for t in self.offered()]
            return self._done(name, shown, t0, error=f"unknown tool '{name}'" + (
                f"; the tools are: {', '.join(names)}" if names else "; no tools are available"))
        try:
            args = validate_arguments(tool.parameters, arguments)
        except ToolError as e:
            return self._done(name, shown, t0, error=str(e))
        box: dict = {}

        def run():
            try:
                kw = dict(args)
                if tool.wants_context:
                    kw["context"] = dict(context or {})
                box["value"] = tool.fn(**kw)
            except ToolError as e:
                box["error"] = str(e)
            except Exception as e:                  # noqa: BLE001 - a tool must never crash the assistant
                log.exception("tool %s failed", name)
                box["error"] = f"the tool failed ({type(e).__name__}: {e})"

        th = threading.Thread(target=run, name=f"marvin-tool-{name}", daemon=True)
        th.start()
        th.join(tool.timeout)
        if th.is_alive():
            return self._done(name, args, t0, error=f"no answer within {tool.timeout:g} s")
        if "error" in box:
            return self._done(name, args, t0, error=box["error"])
        value = box.get("value")
        try:
            content = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
        except (TypeError, ValueError):
            content = str(value)
        return self._done(name, args, t0, content=content)

    def _done(self, name: str, args: dict, t0: float, content: str = "", error: str = "") -> ToolResult:
        seconds = time.monotonic() - t0
        if error:
            log.info("tool %s(%s) failed in %.2f s: %s", name, args, seconds, error)
            res = ToolResult(name, args, False, json.dumps({"error": error}, ensure_ascii=False), seconds, error)
        else:
            log.info("tool %s(%s) in %.2f s: %s", name, args, seconds, _short(content, 160))
            res = ToolResult(name, args, True, content, seconds)
        self.calls.append(res.record())
        return res

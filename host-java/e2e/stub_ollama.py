#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""A stand-in for Ollama: /api/tags, /api/show, /api/chat (streamed NDJSON, tool calls, a </think> leak)."""
import json, os, sys, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

MODELS = ["qwen3:4b-instruct", "qwen3.8:27b-mlx"]
LOG = open(sys.argv[2] if len(sys.argv) > 2 else os.devnull, "a")


def chunk(content="", done=False, tool_calls=None):
    m = {"role": "assistant", "content": content}
    if tool_calls:
        m["tool_calls"] = tool_calls
    d = {"model": "qwen3:4b-instruct", "created_at": "2026-01-01T00:00:00Z", "message": m, "done": done}
    if done:
        d.update(done_reason="stop", total_duration=1, eval_count=10, prompt_eval_count=10)
    return json.dumps(d)


def answer(req):
    msgs = req.get("messages", [])
    last_user = next((m.get("content", "") for m in reversed(msgs) if m.get("role") == "user"), "").lower()
    has_tool = bool(msgs) and msgs[-1].get("role") == "tool"
    if ("weather" in last_user or "météo" in last_user) and not has_tool and req.get("tools"):
        return [chunk(tool_calls=[{"function": {"name": "get_weather", "arguments": {"place": "Nice"}}}]), chunk(done=True)]
    if has_tool:
        tool = next(m.get("content", "") for m in reversed(msgs) if m.get("role") == "tool")
        LOG.write("TOOL RESULT " + tool[:300] + "\n"); LOG.flush()
        # the leak case: the model reasons in the answer, closes the tag, then answers
        return [chunk("Okay, the tool says it is mild. I should answer briefly."), chunk(" </think>"),
                chunk("It is mild in Nice today. "), chunk("Enjoy your day!"), chunk(done=True)]
    if "think" in last_user:
        return [chunk("<think>hidden reasoning</think>"), chunk("I thought about it. "),
                chunk("The answer is forty-two."), chunk(done=True)]
    return [chunk("Hello! "), chunk("I am Marvin, "), chunk("your desk companion. "),
            chunk("How can I help?"), chunk(done=True)]


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith("/api/tags"):
            return self._json(200, {"models": [{"name": m, "model": m, "size": 1} for m in MODELS]})
        if self.path.startswith("/api/version"):
            return self._json(200, {"version": "0.12.0"})
        self._json(404, {"error": "not found"})

    def do_HEAD(self):
        self.send_response(200); self.send_header("Content-Length", "0"); self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        req = json.loads(self.rfile.read(n) or b"{}")
        LOG.write(f"{time.strftime('%H:%M:%S')} POST {self.path} {json.dumps(req)[:400]}\n"); LOG.flush()
        if self.path.startswith("/api/show"):
            return self._json(200, {"capabilities": ["completion", "tools"], "details": {"family": "qwen3"},
                                    "model_info": {"general.architecture": "qwen3"}})
        if self.path.startswith("/api/chat"):
            lines = answer(req)
            if req.get("stream") is False:
                text = "".join(json.loads(l)["message"]["content"] for l in lines)
                return self._json(200, json.loads(chunk(text, done=True)))
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for l in lines:
                data = (l + "\n").encode()
                self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
                self.wfile.flush()
                time.sleep(0.05)
            self.wfile.write(b"0\r\n\r\n")
            return
        self._json(404, {"error": "not found"})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 11434), H).serve_forever()

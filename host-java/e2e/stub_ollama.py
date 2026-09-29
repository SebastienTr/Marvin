#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""A stand-in for Ollama: /api/tags, /api/show, /api/chat (streamed NDJSON, tool calls, a </think> leak, and
structured answers for memory's jobs when the request has a JSON schema in "format"), /api/embed (deterministic
embeddings: texts that share words are close)."""
import json, math, os, re, sys, time, unicodedata
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

MODELS = ["qwen3:4b-instruct", "qwen3.8:27b-mlx", "bge-m3"]
STOP = {"the", "owner", "and", "for", "with", "that", "this", "has", "have", "are", "was", "his", "her", "its", "they",
        "their", "from", "who", "les", "des", "une", "est"}


def embedding(text, dims=1024):
    """The same words-in-buckets embedding as the Java tests' StubOllama (String.hashCode, same stems)."""
    v = [0.0] * dims
    norm = "".join(c for c in unicodedata.normalize("NFD", text.lower()) if not unicodedata.combining(c))
    for w in re.split(r"[^a-z0-9]+", norm):
        if len(w) < 3 or w in STOP:
            continue
        stem = w[:-3] if len(w) > 5 and w.endswith("ing") else w[:-2] if len(w) > 4 and w.endswith("ed") \
            else w[:-1] if len(w) > 4 and w.endswith("s") else w
        h = 0
        for ch in stem:
            h = (31 * h + ord(ch)) & 0xFFFFFFFF
        h = h - (1 << 32) if h >= 1 << 31 else h
        v[h % dims] += 1.0
        h2 = ((h * 31 + 7) & 0xFFFFFFFF)
        h2 = h2 - (1 << 32) if h2 >= 1 << 31 else h2
        v[h2 % dims] += 0.5
    n = math.sqrt(sum(x * x for x in v))
    if n == 0:
        v[0] = 1.0
        return v
    return [x / n for x in v]


FACT_PATTERNS = [
    (r"(?:j'habite|je vis) (?:à|a) ([A-ZÀ-Ý][\w-]+)", "The owner lives in {}."),
    (r"i live in ([A-Z][\w-]+)", "The owner lives in {}."),
    (r"mon chat s'appelle ([A-ZÀ-Ý][\w-]+)", "The owner has a cat named {}."),
    (r"my cat is called ([A-Z][\w-]+)", "The owner has a cat named {}."),
    (r"je m'appelle ([A-ZÀ-Ý][\w-]+)", "The owner's name is {}."),
    (r"my name is ([A-Z][\w-]+)", "The owner's name is {}."),
]


def structured(req):
    """Memory's jobs: what the JSON schema asks for decides the answer."""
    props = (req.get("format") or {}).get("properties", {})
    text = "\n".join(m.get("content", "") for m in req.get("messages", []))
    if "facts" in props:
        convo = text.split("Conversation", 1)[-1]
        facts = []
        for line in convo.splitlines():
            if " Owner: " not in line:
                continue
            said = line.split(" Owner: ", 1)[1]
            for pattern, statement in FACT_PATTERNS:
                for m in re.finditer(pattern, said, re.IGNORECASE):
                    facts.append({"subject": "owner", "statement": statement.format(m.group(1)), "kind": "biographical",
                                  "valid_from": "", "valid_to": "", "importance": 7, "sensitivity": "personal",
                                  "confidence": 0.9})
        return {"facts": facts}
    if "operation" in props:
        return {"operation": "ADD", "target": 0, "statement": "", "valid_to": ""}
    if "summary" in props:
        n = text.count(" Owner: ")
        return {"summary": "The owner spent time near Marvin" + (f" and talked with it {n} times." if n else ".")}
    if "lines" in props:
        learned = text.split("Learned since:", 1)[-1].split("No longer true:", 1)[0]
        previous = text.split("Previous profile:", 1)[-1].split("Learned since:", 1)[0]
        lines = [l.replace("KEEP ", "", 1).strip() for l in previous.splitlines() if l.strip() and l.strip() != "(empty)"]
        lines += [l[2:].strip() for l in learned.splitlines() if l.startswith("- ")]
        return {"lines": lines}
    return {}
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
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            raw = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                raw += self.rfile.read(size)
                self.rfile.readline()
        else:
            raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        req = json.loads(raw or b"{}")
        LOG.write(f"{time.strftime('%H:%M:%S')} POST {self.path} {json.dumps(req)[:400]}\n"); LOG.flush()
        if self.path.startswith("/api/show"):
            return self._json(200, {"capabilities": ["completion", "tools"], "details": {"family": "qwen3"},
                                    "model_info": {"general.architecture": "qwen3"}})
        if self.path.startswith("/api/embed"):
            model = req.get("model", "")
            if model not in MODELS and model + ":latest" not in MODELS:
                return self._json(404, {"error": f'model "{model}" not found, try pulling it first'})
            inputs = req.get("input") or []
            inputs = [inputs] if isinstance(inputs, str) else inputs
            return self._json(200, {"model": model, "embeddings": [embedding(t) for t in inputs],
                                    "total_duration": 1, "load_duration": 0, "prompt_eval_count": 8 * len(inputs)})
        if self.path.startswith("/api/chat"):
            if req.get("format"):
                content = json.dumps(structured(req))
                lines = [chunk(content), chunk(done=True)]
            else:
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

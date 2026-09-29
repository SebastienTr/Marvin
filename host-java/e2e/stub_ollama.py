#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""A stand-in for Ollama: /api/tags, /api/show, /api/chat (streamed NDJSON, tool calls, a </think> leak, and
structured answers for memory's jobs when the request has a JSON schema in "format"), /api/embed (deterministic
embeddings: texts that share words are close)."""
import hashlib, json, math, os, re, sys, time, unicodedata
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


# (pattern, subject, statement, kind, sensitivity): what a good extraction finds in a line the owner said
FACT_PATTERNS = [
    (r"(?:j'habite|je vis) (?:à|a) ([A-ZÀ-Ý][\w-]+)", "owner", "The owner lives in {0}.", "biographical", "personal"),
    (r"i live in ([A-Z][\w-]+)", "owner", "The owner lives in {0}.", "biographical", "personal"),
    (r"(?:we|i) (?:have |just )?moved to ([A-Z][\w-]+)", "owner", "The owner lives in {0}.", "biographical", "personal"),
    (r"(?:nous avons|on a|j'ai) déménagé (?:à|a) ([A-ZÀ-Ý][\w-]+)", "owner", "The owner lives in {0}.", "biographical",
     "personal"),
    (r"mon chat s'appelle ([A-ZÀ-Ý][\w-]+)", "thing:{0}", "The owner has a cat named {0}.", "relation", "normal"),
    (r"my cat is called ([A-Z][\w-]+)", "thing:{0}", "The owner has a cat named {0}.", "relation", "normal"),
    (r"je m'appelle ([A-ZÀ-Ý][\w-]+)", "owner", "The owner's name is {0}.", "biographical", "normal"),
    (r"my name is ([A-Z][\w-]+)", "owner", "The owner's name is {0}.", "biographical", "normal"),
    (r"ma s(?:œ|oe)ur ([A-ZÀ-Ý][\w-]+) (?:habite|vit) (?:à|a) ([A-ZÀ-Ý][\w-]+)", "person:{0}",
     "{0} is the owner's sister and lives in {1}.", "relation", "personal"),
    (r"my sister ([A-Z][\w-]+) lives in ([A-Z][\w-]+)", "person:{0}", "{0} is the owner's sister and lives in {1}.",
     "relation", "personal"),
    (r"i prefer (\w+) to (\w+)", "owner", "The owner prefers {0} to {1}.", "preference", "normal"),
    (r"je préfère le (\w+) au (\w+)", "owner", "The owner prefers {0} to {1}.", "preference", "normal"),
    (r"je travaille (?:chez|à|a) ([A-ZÀ-Ý][\w-]+)", "owner", "The owner works at {0}.", "state", "normal"),
    (r"i work at ([A-Z][\w-]+)", "owner", "The owner works at {0}.", "state", "normal"),
    (r"(?:i am|i'm) allergic to (\w+)", "owner", "The owner is allergic to {0}.", "biographical", "sensitive"),
    (r"je suis allergique aux? (\w+)", "owner", "The owner is allergic to {0}.", "biographical", "sensitive"),
]


def extract(text):
    convo = text.split("Conversation", 1)[-1]
    facts = []
    for line in convo.splitlines():
        if " Owner: " not in line:
            continue
        said = line.split(" Owner: ", 1)[1]
        for pattern, subject, statement, kind, sensitivity in FACT_PATTERNS:
            for m in re.finditer(pattern, said, re.IGNORECASE):
                g = m.groups()
                facts.append({"subject": subject.format(*g), "statement": statement.format(*g), "kind": kind,
                              "valid_from": "", "valid_to": "", "importance": 7, "sensitivity": sensitivity,
                              "confidence": 0.9})
    return {"facts": facts}


def reconcile(text):
    """The same statement: NOOP; the same subject living elsewhere now: INVALIDATE the old one; otherwise ADD."""
    said = re.search(r"Candidate, said on (\d{4}-\d{2}-\d{2}):\n\[([^\]]*)\] ([^\n(]+)", text)
    if not said:
        return {"operation": "ADD", "target": 0, "statement": "", "valid_to": ""}
    day, subject, statement = said.group(1), said.group(2), said.group(3).strip()
    existing = re.findall(r"^(\d+)\. \[([^\]]*)\] ([^\n(]+)", text.split("Existing facts:", 1)[-1], re.MULTILINE)
    for n, s, st in existing:
        if s == subject and st.strip().lower() == statement.lower():
            return {"operation": "NOOP", "target": int(n), "statement": "", "valid_to": ""}
    moved = re.match(r"(.+) lives in (\S+)\.$", statement)
    for n, s, st in existing:
        old = re.match(r"(.+) lives in (\S+)\.$", st.strip())
        if moved and old and s == subject and old.group(1) == moved.group(1) and old.group(2) != moved.group(2):
            return {"operation": "INVALIDATE", "target": int(n), "statement": "", "valid_to": day}
    return {"operation": "ADD", "target": 0, "statement": "", "valid_to": ""}


def structured(req):
    """Memory's jobs: what the JSON schema asks for decides the answer."""
    props = (req.get("format") or {}).get("properties", {})
    text = "\n".join(m.get("content", "") for m in req.get("messages", []))
    if "facts" in props:
        return extract(text)
    if "operation" in props:
        return reconcile(text)
    if "summary" in props:
        said = [l.split(" Owner: ", 1)[1].strip() for l in text.splitlines() if " Owner: " in l]
        if not said:
            return {"summary": "The owner spent time near Marvin."}
        quoted = "; ".join(s[:60].rstrip(" .!?") for s in said[:4])
        return {"summary": f"The owner talked with Marvin {len(said)} times: {quoted}."}
    if "lines" in props:
        learned = text.split("Learned since:", 1)[-1].split("No longer true:", 1)[0]
        ended = text.split("No longer true:", 1)[-1].split("Forgotten by the owner", 1)[0]
        forgotten = text.split("Forgotten by the owner", 1)[1] if "Forgotten by the owner" in text else ""
        previous = text.split("Previous profile:", 1)[-1].split("Learned since:", 1)[0]
        gone = {re.sub(r"^\([^)]*\) ", "", l[2:].strip()).lower() for l in (ended + forgotten).splitlines() if l.startswith("- ")}
        lines = [l.replace("KEEP ", "", 1).strip() for l in previous.splitlines() if l.strip() and l.strip() != "(empty)"]
        # a learned line is "(subject) statement": the profile keeps the statement
        lines += [re.sub(r"^\([^)]*\) ", "", l[2:].strip()) for l in learned.splitlines() if l.startswith("- ")]
        out = []
        for l in lines:
            if l.lower() not in gone and l not in out:
                out.append(l)
        return {"lines": out}
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
    said_last = next((m.get("content", "") for m in reversed(msgs) if m.get("role") == "user"), "")
    last_user = said_last.lower()
    has_tool = bool(msgs) and msgs[-1].get("role") == "tool"
    if ("weather" in last_user or "météo" in last_user) and not has_tool and req.get("tools"):
        return [chunk(tool_calls=[{"function": {"name": "get_weather", "arguments": {"place": "Nice"}}}]), chunk(done=True)]
    offered = {t.get("function", {}).get("name") for t in req.get("tools") or []}
    # the question, not the memory section's heading ("What you remember that may matter here ...")
    said = next((m for m in reversed(list(re.finditer(r"remember that ([^\n.?!]+)", last_user)))
                 if not m.group(1).startswith("may matter")), None)
    if said and "remember" in offered and not has_tool:
        statement = said_last[said.start(1):said.end(1)].strip()      # as it was written
        return [chunk(tool_calls=[{"function": {"name": "remember", "arguments": {"statement": statement}}}]),
                chunk(done=True)]
    forget = ([None] + list(re.finditer(r"(?:forget|oublie) (?:that |que |qu')?([^\n.?!]+)", last_user)))[-1]
    if forget and "forget" in offered and not has_tool:
        return [chunk(tool_calls=[{"function": {"name": "forget", "arguments": {"query": forget.group(1).strip()}}}]),
                chunk(done=True)]
    about = ([None] + list(re.finditer(r"what do you remember about ([^\n.?!]+)", last_user)))[-1]
    if about and "recall" in offered and not has_tool:
        return [chunk(tool_calls=[{"function": {"name": "recall", "arguments": {"query": about.group(1).strip()}}}]),
                chunk(done=True)]
    called = msgs[-2]["tool_calls"][0]["function"]["name"] if has_tool and msgs[-2].get("tool_calls") else ""
    if called == "remember":
        return [chunk("Noted, "), chunk("I will remember it."), chunk(done=True)]
    if called == "forget":
        return [chunk("I found it. "), chunk("Say yes, or confirm in the app, and I will forget it."), chunk(done=True)]
    if called == "recall":
        try:
            found = json.loads(msgs[-1].get("content", "") or "{}")
        except ValueError:
            found = {}
        first = next(iter([f.get("statement", "") for f in found.get("facts", [])] + [d.get("summary", "") for d in found.get("days", [])]), "")
        return [chunk("Here is what I remember: " if first else "I do not remember anything about that."), chunk(first[:200]),
                chunk(done=True)]
    # a question about the owner's home: answered from what memory put in the prompt (the newest mention first)
    if re.search(r"where do i live|où (?:est-ce que )?j'habite|où j'habite", last_user):
        everything = [m.get("content", "") for m in msgs if m.get("role") in ("system", "user")]
        homes = [h for c in reversed(everything) for h in reversed(re.findall(r"owner lives in ([A-Z][\w-]+)", c))]
        return [chunk(f"You live in {homes[0]}." if homes else "I do not know where you live."), chunk(done=True)]
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
            system = next((m.get("content", "") for m in req.get("messages", []) if m.get("role") == "system"), "")
            digest = hashlib.sha1(system.encode()).hexdigest()[:12]
            LOG.write(f"SYSTEM {'memory' if req.get('format') else 'voice'} {digest} {len(system)}\n"); LOG.flush()
            if len(sys.argv) > 2:  # each distinct system prompt next to the log, to compare them
                seen = f"{sys.argv[2]}.system-{digest}.txt"
                if not os.path.exists(seen):
                    with open(seen, "w") as f:
                        f.write(system)
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

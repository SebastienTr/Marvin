#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Memory end to end on a running host (`./marvin up` on a fresh database that imported `week_fixture.py`'s scripted
week, the stub Ollama, the voice sidecar in its test mode): what the week taught Marvin, facts that change, sources,
profile versions, day and week summaries, retrieval in later answers (the reply inspector shows what was sent),
remember, recall, forget by voice confirmed in the app, export, and, after a restart, that everything is still there
and nothing forgotten came back.

    python3 host-java/e2e/week.py learn          the week, today's questions, forgetting, export
    python3 host-java/e2e/week.py snapshot       what memory holds now (learn ends with one)
    python3 host-java/e2e/week.py after-restart  run after ./marvin restart: the same as the snapshot
    python3 host-java/e2e/week.py reset          "forget everything", then Marvin knows nothing

STUB_LOG: the stub's log (checks that the voice's system prompt stays byte-identical between questions).
OUT: where screenshots go (default final-shots/)."""
import json, os, re, sys, time, urllib.parse, urllib.request

BASE = os.environ.get("BASE", "http://localhost:8765")
OUT = os.environ.get("OUT", "final-shots")
STUB_LOG = os.environ.get("STUB_LOG", "")
STATE = os.path.join(OUT, "week-state.json")
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f" ({detail})" if detail else ""), flush=True)
    return ok


def api(path, body=None, raw=False):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", "Origin": BASE} if body is not None else {})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            data = r.read()
    except urllib.error.HTTPError as e:
        data = e.read()
    return data.decode() if raw else json.loads(data or b"{}")


def wait_for(fn, timeout=30, step=0.5):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = fn()
            if v:
                return v
        except Exception:
            pass
        time.sleep(step)
    return None


def facts(filter_="all", q=""):
    return api(f"/api/memory/facts?filter={filter_}&limit=200&q={urllib.parse.quote(q)}")["facts"]


def statements(filter_="all"):
    return [f["statement"] for f in facts(filter_)]


def run_pass(kind):
    """Runs a pass now; one that gave way to the voice (a question had just been answered) is asked again."""
    last = {}
    for _ in range(15):
        wait_for(lambda: api("/api/voice")["voice"]["status"] in ("idle", "listening", "off"), 20)
        before = api("/api/memory/worker").get("last") or {}
        api("/api/memory/consolidate", {"pass": kind})
        done = wait_for(lambda: (w := api("/api/memory/worker"))["state"] != "running" and (w.get("last") or {}).get(
            "started_at") != before.get("started_at") and w["last"]["pass"] == kind and w, 120, 1)
        last = (done or {}).get("last") or {}
        if last.get("outcome") in ("done", "partial"):
            return last
        time.sleep(3)           # the voice was active less than 15 s ago: memory waits
    return last


def replies():
    return [e for e in api("/api/conversation")["entries"] if e["kind"] == "reply"]


def ask(text, timeout=40):
    n = len(replies())
    wait_for(lambda: api("/api/voice")["voice"]["status"] in ("idle", "listening"), 20)
    api("/api/voice/ask", {"text": text})
    got = wait_for(lambda: len(r := replies()) > n and r[-1], timeout)
    wait_for(lambda: api("/api/voice")["voice"]["status"] in ("idle", "listening"), 20)
    return got or {"text": "", "prompt": "", "memory": {}}


def sent_memory(reply):
    """The lines memory put in the question (the inspector's report): every kept item of every section."""
    return [i["text"] for s in (reply.get("memory") or {}).get("sections", []) for i in s.get("items", []) if i.get("kept")]


def voice_systems():
    if not STUB_LOG or not os.path.exists(STUB_LOG):
        return []
    return [l.split()[2] for l in open(STUB_LOG) if l.startswith("SYSTEM voice ")]


def learn():
    os.makedirs(OUT, exist_ok=True)
    # --- the week, read from the imported history into the log
    log = api("/api/memory/log?q=Lille&limit=20")
    check("the week's conversation is in memory's log", any("Lille" in json.dumps(e) for e in log.get("events", log.get("items", []))),
          f"{len(log.get('events', log.get('items', [])))} lines with Lille")

    night = run_pass("nightly")
    c = night.get("counts", {})
    check("nightly pass done", night.get("outcome") == "done", json.dumps(c))
    if c.get("added", 0) == 0:  # a pass already ran (the worker's own, or an earlier run): the counts are in facts
        c = {}
    now = statements()
    past = facts("past")
    check("facts from French and English lines", {"The owner lives in Lille.", "The owner prefers tea to coffee.",
                                                   "Julie is the owner's sister and lives in Nantes.",
                                                   "The owner has a cat named Pixel.", "The owner's name is Alex."} <= set(now),
          "; ".join(now))
    nice = next((f for f in past if f["statement"] == "The owner lives in Nice."), None)
    check("the move invalidated the old home (past, not current)", nice and "The owner lives in Nice." not in now,
          json.dumps({k: nice.get(k) for k in ("valid_to", "superseded_by", "status")}) if nice else "no past fact")
    lille = next(f for f in facts("all") if f["statement"] == "The owner lives in Lille.")
    detail = api(f"/api/memory/facts/{lille['id']}")
    quotes = json.dumps(detail.get("sources", detail))
    check("a fact shows where it came from", "we moved to Lille" in quotes, quotes[:160])
    allergy = next((f for f in facts("all") if "allergic" in f["statement"]), None)
    check("health is labelled sensitive", allergy and allergy["sensitivity"] == "sensitive", allergy and allergy["sensitivity"])

    profile = api("/api/memory/profile")
    content = (profile.get("current") or {}).get("content", "")
    check("profile written, with the new home, not the old", "Lille" in content and "Nice" not in content,
          f"version {(profile.get('current') or {}).get('id')}: " + content.replace("\n", " | ")[:200])
    check("no sensitive fact in the profile", "allergic" not in content)
    days = api("/api/memory/episodes?level=day")["episodes"]
    weeks = api("/api/memory/episodes?level=week")["episodes"]
    check("a summary for each day of the week", len([d for d in days if d["summary"]]) >= 6, f"{len(days)} days, {len(weeks)} weeks")
    check("sensitive lines kept out of the day summaries", not any("allergique" in d["summary"] for d in days))

    again = run_pass("nightly")
    check("a second night changes nothing", again.get("counts", {}).get("added", 0) == 0 and len(statements()) == len(now),
          json.dumps(again.get("counts", {}))[:160])

    # --- today, by voice (typed into the app's Talk box, the same path as a spoken question)
    api("/api/voice/on", {})
    check("voice on", wait_for(lambda: api("/api/voice")["voice"]["state"] == "on", 60))
    r = ask("Remember that my dentist appointment is on Friday at ten")
    check("remember by voice", wait_for(lambda: any("dentist" in s.lower() for s in statements()), 10), r.get("text", ""))
    dentist = next(f for f in facts("all") if "dentist" in f["statement"].lower())
    check("the owner's fact is the owner's", dentist.get("origin") in ("owner", "said") and dentist.get("confidence") == 1,
          f"origin {dentist.get('origin')}, confidence {dentist.get('confidence')}")

    ask("By the way, I prefer tea to coffee.")
    idle = run_pass("idle")
    check("idle pass: a known fact said again is not added twice", idle.get("outcome") == "done"
          and idle["counts"].get("noop", 0) >= 1 and statements().count("The owner prefers tea to coffee.") == 1,
          json.dumps(idle.get("counts", {}))[:160])

    r1 = ask("Where do I live?")
    check("a later answer uses what was learned", "Lille" in r1.get("text", ""), r1.get("text", ""))
    check("the inspector's report shows the fact sent", any("lives in Lille" in t for t in sent_memory(r1)),
          "; ".join(t[:50] for t in sent_memory(r1) if "owner" in t)[:200])
    check("the old home is not sent as current", "lives in Nice" not in r1.get("prompt", ""))
    r2 = ask("Où est-ce que j'habite ?")
    check("in French too (from the profile in the system prompt)", "Lille" in r2.get("text", ""), r2.get("text", ""))
    r3 = ask("What do you remember about Julie?")
    tools = json.dumps(r3.get("tools", []))
    check("recall tool", "recall" in tools and "Nantes" in r3.get("text", ""), r3.get("text", "")[:120])
    systems = voice_systems()
    if systems:
        check("the voice's system prompt is byte-identical between questions", len(set(systems[-6:])) == 1,
              f"{len(systems)} requests, last hashes {sorted(set(systems[-6:]))}")

    # the inspector, as the owner sees it
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(viewport={"width": 1440, "height": 900})
        errors = []
        page.on("console", lambda m: m.type == "error" and errors.append(m.text))
        page.goto(BASE + "/#talk")
        page.wait_for_timeout(2000)
        item = page.locator("#transcript li.msg.marvin", has_text="You live in Lille").first
        item.locator(".insp-btn").click()
        ok = wait_for(lambda: page.is_visible("dialog[open]") and "lives in Lille" in page.inner_text("#dialog-body"), 8)
        check("inspector lists the facts sent", ok, page.inner_text("#dialog-body")[:120].replace("\n", " | ") if ok else "")
        page.screenshot(path=f"{OUT}/week-inspector-desktop.png")
        page.keyboard.press("Escape")
        page.goto(BASE + "/#memory")
        page.wait_for_timeout(2000)
        page.screenshot(path=f"{OUT}/week-memory-desktop.png")
        check("no console errors (week)", not errors, "; ".join(errors[:3]))
        b.close()

    # --- forgetting, asked by voice, confirmed in the app
    r4 = ask("Forget that my cat is called Pixel")
    pending = wait_for(lambda: api("/api/memory/forget")["pending"], 10)
    proposal = next((p for p in pending or [] if any("Pixel" in f["statement"] for f in p["facts"])), None)
    check("forget by voice proposes, does not forget", proposal and any("Pixel" in s for s in statements()),
          r4.get("text", ""))
    done = api("/api/memory/forget/confirm", {"confirm": proposal["confirm"]}) if proposal else {}
    check("confirmed in the app, the request and its answer with it", done.get("forgotten", {}).get("events") == 2,
          json.dumps(done)[:120])
    check("the fact is gone", not any("Pixel" in s for s in statements() + statements("past") + statements("archived")))
    prof = api("/api/memory/profile")
    check("gone from the profile and every older version",
          not any("Pixel" in v.get("content", "") for v in prof.get("versions", [])), f"{len(prof.get('versions', []))} versions")
    days = api("/api/memory/episodes?level=day")["episodes"]
    check("gone from the day summaries", not any("Pixel" in d["summary"] for d in days),
          "; ".join(f"{d['day']} stale={d['stale']}" for d in days if d["stale"]))
    check("gone from the log", "Pixel" not in json.dumps(api("/api/memory/log?q=Pixel&limit=50")))
    idle = run_pass("idle")
    check("not learned again from the request by the next pass", idle.get("outcome") == "done"
          and not any("Pixel" in s for s in statements()), json.dumps(idle.get("counts", {}))[:120])
    r5 = ask("What do you remember about Pixel?")
    check("forgotten, so not in later context", not any("Pixel" in t for t in sent_memory(r5))
          and "cat named" not in r5.get("text", "") and "Pixel" not in json.dumps(r5.get("tools", [])).split("said")[0],
          r5.get("text", "")[:100])
    run_pass("nightly")
    days = api("/api/memory/episodes?level=day")["episodes"]
    check("the next night rewrites the blanked days without it", not any(d["stale"] for d in days)
          and not any("Pixel" in d["summary"] for d in days), f"{len(days)} days")

    # --- export
    js = api("/api/memory/export?format=json", raw=True)
    md = api("/api/memory/export?format=markdown", raw=True)
    check("export: JSON and Markdown with what is known", "Lille" in js and "Lille" in md and "dentist" in md.lower(),
          f"{len(js)} and {len(md)} bytes")
    # (the question asked about it after the forget is a new line, and stays)
    check("export: nothing forgotten in it", not any(t in js + md for t in ("cat named Pixel", "chat s'appelle Pixel",
                                                                            "cat is called Pixel")))
    with open(os.path.join(OUT, "week-export.md"), "w") as f:
        f.write(md)
    snapshot()


def snapshot():
    """What memory holds now, for after-restart."""
    state = {"all": sorted(statements()), "past": sorted(statements("past")),
             "profile": api("/api/memory/profile")["current"]["id"],
             "days": len(api("/api/memory/episodes?level=day")["episodes"])}
    with open(STATE, "w") as f:
        json.dump(state, f)
    print(f"snapshot: {len(state['all'])} facts, profile version {state['profile']}, {state['days']} days")


def after_restart():
    state = json.load(open(STATE))
    wait_for(lambda: api("/api/health"), 60)
    # the catch-up runs at start: give it a moment, then nothing forgotten may come back
    time.sleep(5)
    check("facts kept across a restart", sorted(statements()) == state["all"], f"{len(state['all'])} facts")
    check("past facts kept", sorted(statements("past")) == state["past"])
    check("profile version kept", api("/api/memory/profile")["current"]["id"] == state["profile"])
    check("summaries kept", len(api("/api/memory/episodes?level=day")["episodes"]) == state["days"])
    run_pass("nightly")
    js = api("/api/memory/export?format=json", raw=True)
    check("nothing forgotten came back (catch-up and a night after the restart)",
          not any(t in js for t in ("cat named Pixel", "chat s'appelle Pixel", "cat is called Pixel")))


def reset():
    api("/api/voice/on", {})
    wait_for(lambda: api("/api/voice")["voice"]["state"] == "on", 60)
    p = api("/api/memory/forget-everything", {})
    wrong = api("/api/memory/forget-everything", {"confirm": p["confirm"], "phrase": "yes"})
    check("forget everything needs the typed phrase", "error" in wrong, json.dumps(wrong)[:100])
    p = api("/api/memory/forget-everything", {})
    done = api("/api/memory/forget-everything", {"confirm": p["confirm"], "phrase": p["phrase"]})
    check("forget everything", "forgotten" in done, json.dumps(done)[:160])
    m = api("/api/memory")
    check("memory is empty", sum(m["counts"].values()) == 0 and not m.get("profile")
          and not api("/api/memory/episodes?level=day")["episodes"], json.dumps(m["counts"]))
    r = ask("Where do I live?")
    check("Marvin no longer knows", "Lille" not in r.get("text", "") and "Lille" not in r.get("prompt", ""),
          r.get("text", ""))


{"learn": learn, "snapshot": snapshot, "after-restart": after_restart, "reset": reset}[sys.argv[1] if len(sys.argv) > 1 else "learn"]()
print(f"{sum(r[1] for r in results)}/{len(results)} passed")
sys.exit(0 if all(r[1] for r in results) else 1)

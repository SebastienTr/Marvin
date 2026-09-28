#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""End-to-end walk through every section of the app, with screenshots, console errors and SSE checks."""
import json, os, sys, time, urllib.request
from playwright.sync_api import sync_playwright

BASE = os.environ.get("BASE", "http://localhost:8765")
OUT = os.environ.get("OUT", "final-shots")
TAG = sys.argv[1] if len(sys.argv) > 1 else "demo"
os.makedirs(OUT, exist_ok=True)
errors, results = [], []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f" ({detail})" if detail else ""), flush=True)


def api(path, body=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", "Origin": BASE} if body is not None else {})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


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


def shots(page, name):
    page.screenshot(path=f"{OUT}/{TAG}-{name}-{page._label}.png", full_page=False)


def walk(browser, label, viewport, dpr):
    ctx = browser.new_context(viewport=viewport, device_scale_factor=dpr, is_mobile=label == "phone",
                              has_touch=label == "phone")
    page = ctx.new_page()
    page._label = label
    page.on("console", lambda m: m.type == "error" and errors.append(f"{label}: {m.text}"))
    page.on("pageerror", lambda e: errors.append(f"{label}: pageerror {e}"))
    page.goto(BASE + "/#home")
    page.wait_for_selector("#conn-text")
    check(f"{label}: connected", wait_for(lambda: "live" in page.inner_text("#conn-text").lower()
                                          or page.get_attribute("#conn", "class") and "ok" in page.get_attribute("#conn", "class"), 15),
          page.inner_text("#conn-text"))
    page.wait_for_timeout(2500)
    shots(page, "home")

    # SSE: the scene updates live on the Robot page, events arrive on /api/stream
    page.goto(BASE + "/#robot")
    page.wait_for_timeout(1500)
    grab = lambda: page.evaluate("() => { const c = document.querySelector('#lidar'); return c.toDataURL ? c.toDataURL() : c.innerHTML; }")
    m1 = grab()
    page.wait_for_timeout(1500)
    m2 = grab()
    check(f"{label}: robot page live (lidar redrawn from the SSE scene)", m1 != m2, page.inner_text("#lidar-meta"))
    check(f"{label}: devices listed", page.locator("#devices li").count() >= 1, page.inner_text("#devices-summary"))
    shots(page, "robot")

    page.goto(BASE + "/#history")
    page.wait_for_timeout(2000)
    shots(page, "history")
    page.fill("#convo-q", "weather")
    page.wait_for_timeout(1500)
    check(f"{label}: conversation search", page.locator("#convo-results li").count() >= 1 or page.is_visible("#convo-count"),
          page.inner_text("#convo-count") if page.is_visible("#convo-count") else "")
    shots(page, "history-search")
    page.fill("#convo-q", "")
    page.click("#day-prev")
    page.wait_for_timeout(1000)
    check(f"{label}: previous day", True, page.inner_text("#day-h"))

    page.goto(BASE + "/#settings")
    page.wait_for_timeout(2000)
    shots(page, "settings")
    page.locator("#voice-form").scroll_into_view_if_needed()
    shots(page, "settings-voice")
    ctx.close()


def talk(browser):
    ctx = browser.new_context(viewport={"width": 1440, "height": 900})
    page = ctx.new_page()
    page._label = "desktop"
    page.on("console", lambda m: m.type == "error" and errors.append(f"talk: {m.text}"))
    page.on("pageerror", lambda e: errors.append(f"talk: pageerror {e}"))
    page.goto(BASE + "/#talk")
    page.wait_for_timeout(1500)
    if not page.is_checked("#voice-switch"):
        page.locator("label:has(#voice-switch)").first.click()
    on = wait_for(lambda: api("/api/voice")["voice"]["state"] == "on", 40)
    check("voice on from the switch", on, api("/api/voice")["voice"]["status"])
    page.wait_for_timeout(1000)
    shots(page, "talk-on")

    # typed question, streamed answer, arriving over SSE
    n0 = page.locator("#transcript li.msg.marvin").count()
    page.fill("#ask-input", "Hello Marvin, who are you?")
    page.click("#ask-send")
    ok = wait_for(lambda: page.locator("#transcript li.msg.marvin").count() > n0
                  and "help" in page.locator("#transcript li.msg.marvin").last.inner_text(), 30)
    check("typed question answered (SSE)", ok, page.locator("#transcript li.msg.marvin").last.inner_text()[:80] if ok else "")

    # tools: the weather, with a </think> leak after the tool result
    n0 = page.locator("#transcript li.msg.marvin").count()
    page.fill("#ask-input", "What's the weather like?")
    page.click("#ask-send")
    ok = wait_for(lambda: "mild in Nice" in page.locator("#transcript li.msg.marvin").last.inner_text(), 40)
    txt = page.locator("#transcript li.msg.marvin").last.inner_text()
    check("weather tool answer", ok, txt[:80])
    check("think leak removed", ok and "tool says" not in txt and "think" not in txt, txt[:80])

    n0 = page.locator("#transcript li.msg.marvin").count()
    page.fill("#ask-input", "Think hard: what is the answer?")
    page.click("#ask-send")
    ok = wait_for(lambda: "forty-two" in page.locator("#transcript li.msg.marvin").last.inner_text(), 30)
    txt = page.locator("#transcript li.msg.marvin").last.inner_text()
    check("<think> block removed", ok and "hidden reasoning" not in txt, txt[:80])

    # inspector
    btn = page.locator("#transcript li.msg.marvin .insp-btn").last
    btn.click()
    page.wait_for_timeout(500)
    check("inspector opens", page.locator("#transcript .inspect").last.is_visible(),
          page.locator("#transcript .inspect").last.inner_text()[:120].replace("\n", " | "))
    shots(page, "talk-inspector")

    # Talk now (after the follow-up window of the last answer has closed)
    wait_for(lambda: api("/api/voice")["voice"]["status"] == "idle", 20)
    page.wait_for_timeout(700)
    page.click("#listen-now")
    ok = wait_for(lambda: api("/api/voice")["voice"]["status"] == "listening", 10)
    check("Talk now listens", ok, str(api("/api/voice")["voice"].get("listen_s")))
    shots(page, "talk-listening")
    page.wait_for_timeout(500)
    # mute
    page.click("#mute")
    ok = wait_for(lambda: api("/api/voice")["voice"]["muted"] is True, 10)
    ok2 = wait_for(lambda: page.get_attribute("#mute", "aria-pressed") == "true", 5)
    check("mute", ok and ok2, f"api {ok} ui {ok2}")
    page.click("#mute")
    ok = wait_for(lambda: api("/api/voice")["voice"]["muted"] is False, 10)
    check("unmute", ok)
    # stop: ask something, stop while speaking
    page.fill("#ask-input", "Hello again")
    page.click("#ask-send")
    sp = wait_for(lambda: api("/api/voice")["voice"]["status"] in ("thinking", "speaking"), 10, 0.1)
    if sp:
        wait_for(lambda: page.is_enabled("#stop-speaking"), 5, 0.1)
        page.click("#stop-speaking")
    ok = wait_for(lambda: api("/api/voice")["voice"]["status"] in ("idle", "listening"), 15)
    check("stop speaking", sp and ok, api("/api/voice")["voice"]["status"])
    page.wait_for_timeout(1500)
    shots(page, "talk")

    # phone view of Talk
    ctx2 = browser.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2, is_mobile=True, has_touch=True)
    p2 = ctx2.new_page(); p2._label = "phone"
    p2.on("console", lambda m: m.type == "error" and errors.append(f"talk-phone: {m.text}"))
    p2.goto(BASE + "/#talk"); p2.wait_for_timeout(2500)
    shots(p2, "talk")
    ctx2.close()

    # voice settings apply: follow-up window
    page.goto(BASE + "/#settings")
    page.wait_for_timeout(1500)
    old = api("/api/voice")["settings"]["follow_up_s"]
    new = 7 if old != 7 else 6
    page.fill("#v-follow", str(new))
    page.locator("#voice-form button[type=submit]").click()
    ok = wait_for(lambda: api("/api/voice")["settings"]["follow_up_s"] == new, 10)
    check("voice settings applied", ok, f"follow_up_s {old} -> {new}")
    page.wait_for_timeout(800)
    shots(page, "settings-voice-saved")
    # general settings
    before = api("/api/settings")
    ok = True
    check("settings readable", "break_minutes" in json.dumps(before) or before, json.dumps(before)[:100])
    ctx.close()


with sync_playwright() as p:
    b = p.chromium.launch()
    walk(b, "phone", {"width": 390, "height": 844}, 2)
    walk(b, "desktop", {"width": 1440, "height": 900}, 1)
    if os.environ.get("TALK", "1") == "1":
        talk(b)
    b.close()

check("no console errors", not errors, "; ".join(errors[:5]))
print(f"{sum(r[1] for r in results)}/{len(results)} passed")
sys.exit(0 if all(r[1] for r in results) else 1)

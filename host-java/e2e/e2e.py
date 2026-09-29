#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""End-to-end walk through the Java host's app, the way a person would use it: every destination in both
appearances at phone and desktop sizes (screenshots), the live robot page, History, the voice (typed questions,
the weather tool, the remember tool and its chip, the reply inspector, Talk now, mute, stop), a decision on Home,
the Home layout, the appearance (Auto follows the system, the geometry does not move), console errors."""
import json, os, sys, time, urllib.request
from playwright.sync_api import sync_playwright

BASE = os.environ.get("BASE", "http://localhost:8765")
OUT = os.environ.get("OUT", "final-shots")
TAG = sys.argv[1] if len(sys.argv) > 1 else "demo"
os.makedirs(OUT, exist_ok=True)
errors, results = [], []
SIZES = {"phone": ({"width": 390, "height": 844}, 2), "desktop": ({"width": 1440, "height": 900}, 1)}
SCREENS = ["home", "talk", "memory", "activity", "activity/history", "activity/log", "marvin", "marvin/robot",
           "marvin/voice", "marvin/preferences"]


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


def context(browser, size, theme):
    viewport, dpr = SIZES[size]
    ctx = browser.new_context(viewport=viewport, device_scale_factor=dpr, is_mobile=size == "phone", has_touch=size == "phone")
    ctx.add_init_script(f"try {{ if (!sessionStorage.getItem('e2e')) {{ sessionStorage.setItem('e2e', '1'); "
                        f"localStorage.setItem('marvin.appearance', JSON.stringify('{theme}')); localStorage.removeItem('marvin.home'); }} }} catch (e) {{}}")
    page = ctx.new_page()
    page.on("console", lambda m: m.type == "error" and errors.append(f"{size}/{theme}: {m.text}"))
    page.on("pageerror", lambda e: errors.append(f"{size}/{theme}: pageerror {e}"))
    return ctx, page


def shot(page, name):
    page.screenshot(path=f"{OUT}/{TAG}-{name}.png", full_page=False)


def gallery(browser):
    """Every screen, both appearances, both sizes."""
    for size in SIZES:
        for theme in ("day", "night"):
            ctx, page = context(browser, size, theme)
            page.goto(BASE + "/#home")
            ok = wait_for(lambda: page.get_attribute("#conn", "data-state") in ("live", "offline"), 15)
            if theme == "day":
                check(f"{size}: connected", ok, page.inner_text("#conn-text") if size == "desktop" else "")
            check(f"{size}/{theme}: appearance applied", page.get_attribute("html", "data-theme") == theme)
            for s in SCREENS:
                page.goto(f"{BASE}/#{s}")
                page.wait_for_timeout(1400)
                shot(page, f"{s.replace('/', '-')}-{theme}-{size}")
            ctx.close()


def robot_and_history(browser):
    ctx, page = context(browser, "desktop", "day")
    page.goto(BASE + "/#marvin/robot")
    page.wait_for_timeout(1500)
    grab = lambda: page.evaluate("() => document.querySelector('#lidar').toDataURL()")
    m1 = grab()
    page.wait_for_timeout(1500)
    check("robot page live (lidar redrawn from the SSE scene)", m1 != grab(), page.inner_text("#lidar-meta"))
    check("devices listed", page.locator("#devices li").count() >= 1, page.inner_text("#devices-summary"))
    check("the robot's screen is drawn by the host", page.evaluate("() => document.querySelector('#face').naturalWidth") == 240)
    page.goto(BASE + "/#robot")                        # the old address still works
    page.wait_for_timeout(500)
    check("old #robot address opens Marvin > Robot", page.is_visible("#lidar"))
    page.goto(BASE + "/#activity/history")
    page.wait_for_timeout(1500)
    page.fill("#convo-q", "weather")
    page.wait_for_timeout(1500)
    check("conversation search", page.locator("#convo-results li").count() >= 1 or page.is_visible("#convo-count"),
          page.inner_text("#convo-count"))
    page.fill("#convo-q", "")
    page.click("#day-prev")
    page.wait_for_timeout(1000)
    check("previous day", page.inner_text("#hday-h") == "Day", page.inner_text("#day [data-f=label]"))
    page.goto(BASE + "/#activity/log")
    page.wait_for_timeout(1000)
    check("log", page.locator("#log li").count() >= 1)
    ctx.close()


def home(browser):
    ctx, page = context(browser, "desktop", "auto")
    page.goto(BASE + "/#home")
    page.wait_for_timeout(1500)
    page.emulate_media(color_scheme="dark")
    page.wait_for_timeout(200)
    dark = page.get_attribute("html", "data-theme")
    page.emulate_media(color_scheme="light")
    page.wait_for_timeout(200)
    check("Auto follows the system, live", dark == "night" and page.get_attribute("html", "data-theme") == "day")
    boxes = "n => n.map(x => JSON.stringify(x.getBoundingClientRect()))"
    page.click("[data-theme-choice=day]")
    g1 = page.eval_on_selector_all("#home-modules > *, .top, .sidebar", boxes)
    page.click("[data-theme-choice=night]")
    g2 = page.eval_on_selector_all("#home-modules > *, .top, .sidebar", boxes)
    check("same geometry in both appearances", g1 == g2)
    page.reload()
    page.wait_for_timeout(800)
    check("appearance kept in this browser", page.get_attribute("html", "data-theme") == "night")
    # layout: hide a module, move one, keyboard only; kept after a reload; reset
    page.click("#customize")
    page.wait_for_selector("dialog[open] .module-choice")
    page.uncheck(".module-choice:has-text('Recent moments') input")
    page.focus(".module-choice:has-text('A small thing') button[data-dir='-1']")
    page.keyboard.press("Enter")
    page.keyboard.press("Escape")
    page.reload()
    page.wait_for_timeout(1200)
    order = page.eval_on_selector_all("#home-modules > *", "n => n.map(x => (x.dataset.module || x.id) + (x.hidden ? '-' : ''))")
    check("Home layout kept, decisions never hidden", order[:3] == ["presence", "home-decision", "memory"] and "moments-" in order, str(order))
    page.goto(BASE + "/#marvin/preferences")
    page.click("#reset-layout-2")
    page.goto(BASE + "/#home")
    page.wait_for_timeout(500)
    order = page.eval_on_selector_all("#home-modules > *", "n => n.map(x => (x.dataset.module || x.id) + (x.hidden ? '-' : ''))")
    check("Home layout reset", order == ["presence", "home-decision", "day", "memory", "body", "moments"], str(order))
    # a decision: a forget proposal made in the app waits on Home until confirmed or dropped
    listed = api("/api/memory/facts?filter=all")
    facts = listed["facts"]
    if facts:
        api("/api/memory/facts/forget", {"id": facts[0]["id"]})
        ok = wait_for(lambda: page.inner_text("#decision-pill").endswith(" pending") and "Nothing" not in page.inner_text("#decision-pill"), 10)
        check("a decision appears on Home", ok, page.inner_text("#decision-body")[:80].replace("\n", " | "))
        shot(page, "home-decision-desktop")
        page.click("#decision-body button:has-text('Keep')")
        ok = wait_for(lambda: page.inner_text("#decision-pill") == "Nothing pending", 10)
        kept = api(f"/api/memory/facts/{facts[0]['id']}")["fact"]["status"] == "current"
        check("Keep drops the proposal, nothing forgotten", ok and kept and not api("/api/memory/forget")["pending"])
    ctx.close()


def talk(browser):
    ctx, page = context(browser, "desktop", "day")
    page.goto(BASE + "/#talk")
    page.wait_for_timeout(1500)
    if page.get_attribute("#voice-switch", "aria-pressed") != "true":
        page.click("#voice-switch")
    on = wait_for(lambda: api("/api/voice")["voice"]["state"] == "on", 40)
    check("voice on from the switch", on, api("/api/voice")["voice"]["status"])
    page.wait_for_timeout(1000)
    last = lambda: page.locator("#transcript li.msg.marvin:not(.live)").last.inner_text()

    n0 = page.locator("#transcript li.msg.marvin").count()
    page.fill("#ask-input", "Hello Marvin, who are you?")
    page.click("#ask-send")
    ok = wait_for(lambda: page.locator("#transcript li.msg.marvin:not(.live)").count() > n0 and "help" in last(), 30)
    check("typed question answered (SSE)", ok, last()[:80] if ok else "")

    page.fill("#ask-input", "What's the weather like?")
    page.click("#ask-send")
    ok = wait_for(lambda: "mild in Nice" in last(), 40)
    check("weather tool answer", ok, last()[:80])
    check("think leak removed", ok and "tool says" not in last(), last()[:80])

    page.fill("#ask-input", "Remember that I water the plants on Sundays")
    page.click("#ask-send")
    ok = wait_for(lambda: page.locator("#transcript .memory-chip").count() >= 1, 30)
    check("remember tool leaves a chip", ok, page.locator("#transcript .memory-chip").last.inner_text() if ok else "")
    check("the remembered fact is in memory", wait_for(lambda: any("plants" in f["statement"] for f in api("/api/memory/facts?filter=all")["facts"]), 10))

    page.fill("#ask-input", "Think hard: what is the answer?")
    page.click("#ask-send")
    ok = wait_for(lambda: "forty-two" in last(), 30)
    check("<think> block removed", ok and "hidden reasoning" not in last(), last()[:80])

    page.locator("#transcript li.msg.marvin .insp-btn").last.click()
    body = lambda: page.inner_text("#dialog-body").lower()
    ok = wait_for(lambda: page.is_visible("dialog[open]") and "where the time went" in body(), 5)
    check("inspector opens with timings and memory", ok and "memory in this answer" in body(),
          page.inner_text("#dialog-body")[:100].replace("\n", " | "))
    shot(page, "talk-inspector-desktop")
    page.keyboard.press("Escape")

    wait_for(lambda: api("/api/voice")["voice"]["status"] == "idle", 20)
    page.wait_for_timeout(700)
    page.click("#listen-now")
    ok = wait_for(lambda: api("/api/voice")["voice"]["status"] == "listening", 10)
    check("Talk now listens", ok, str(api("/api/voice")["voice"].get("listen_s")))
    shot(page, "talk-listening-desktop")
    page.wait_for_timeout(500)
    page.click("#mute")
    ok = wait_for(lambda: api("/api/voice")["voice"]["muted"] is True, 10)
    ok2 = wait_for(lambda: page.get_attribute("#mute", "aria-pressed") == "true", 5)
    check("mute", ok and ok2, f"api {ok} ui {ok2}")
    page.click("#mute")
    check("unmute", wait_for(lambda: api("/api/voice")["voice"]["muted"] is False, 10))
    page.fill("#ask-input", "Hello again")
    page.click("#ask-send")
    sp = wait_for(lambda: api("/api/voice")["voice"]["status"] in ("thinking", "speaking"), 10, 0.1)
    if sp:
        wait_for(lambda: page.is_enabled("#stop-speaking"), 5, 0.1)
        shot(page, "talk-speaking-desktop")
        page.click("#stop-speaking")
    ok = wait_for(lambda: api("/api/voice")["voice"]["status"] in ("idle", "listening"), 15)
    check("stop speaking", sp and ok, api("/api/voice")["voice"]["status"])

    page.goto(BASE + "/#marvin/voice")
    page.wait_for_timeout(1500)
    old = api("/api/voice")["settings"]["follow_up_s"]
    new = 7 if old != 7 else 6
    page.fill("#v-follow", str(new))
    page.locator("#voice-form button[type=submit]").click()
    check("voice settings applied", wait_for(lambda: api("/api/voice")["settings"]["follow_up_s"] == new, 10), f"follow_up_s {old} -> {new}")
    ctx.close()


with sync_playwright() as p:
    b = p.chromium.launch()
    robot_and_history(b)
    if os.environ.get("TALK", "1") == "1":
        talk(b)
    home(b)
    gallery(b)
    b.close()

check("no console errors", not errors, "; ".join(errors[:5]))
print(f"{sum(r[1] for r in results)}/{len(results)} passed")
sys.exit(0 if all(r[1] for r in results) else 1)

#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""End-to-end walk through the Java host's app, the way a person would use it: every destination in both
appearances at phone and desktop sizes (screenshots), the live robot page, History, the voice (typed questions,
the weather tool, the remember tool and its chip, the reply inspector, Talk now, mute, stop), a decision on Home,
the Home layout, the appearance (Auto follows the system, the geometry does not move), Memory (add, correct, pin,
filters, search, forget with its second choice, the profile and its versions, export, the raw log, settings, a
source switch, "forget everything" up to its typed phrase), Marvin > System (services, the log), the lost-connection
banner, 320 px without sideways scrolling, console errors. The facts it adds are forgotten again at the end."""
import json, os, sys, time, urllib.request
from playwright.sync_api import sync_playwright

BASE = os.environ.get("BASE", "http://localhost:8765")
OUT = os.environ.get("OUT", "final-shots")
TAG = sys.argv[1] if len(sys.argv) > 1 else "demo"
os.makedirs(OUT, exist_ok=True)
errors, results = [], []
SIZES = {"phone": ({"width": 390, "height": 844}, 2), "desktop": ({"width": 1440, "height": 900}, 1)}
SCREENS = ["home", "talk", "memory", "activity", "activity/history", "marvin", "marvin/robot", "marvin/voice",
           "marvin/system", "marvin/preferences"]


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
    page.goto(BASE + "/#activity/log")                 # the old address of the log
    page.wait_for_timeout(1000)
    check("old #activity/log address opens Marvin > System", page.evaluate("location.hash") == "#marvin/system")
    check("log", page.locator("#log li").count() >= 1)
    check("services listed", page.locator("#services .service").count() >= 4, page.inner_text("#services-summary"))
    page.fill("#log-q", "zzzz-nothing")
    page.wait_for_timeout(400)
    check("log filter", page.locator("#log li").count() == 0 and page.is_visible("#log-empty"))
    ctx.close()


def memory(browser):
    """Memory's screen, end to end, on the real API. What it adds, it forgets."""
    ctx, page = context(browser, "desktop", "day")
    page.goto(BASE + "/#memory")
    page.wait_for_selector("#memory-count:not(:empty)")
    page.click("#memory-add")
    page.fill("#remember-text", "The e2e walk keeps a lemon tree on the balcony.")
    page.click("dialog button.primary")
    ok = wait_for(lambda: page.locator("#fact-list .fact:has-text('lemon tree')").count() == 1, 10)
    check("memory: add", ok)
    page.click("#fact-list .fact:has-text('lemon tree') button:has-text('Source & edit')")
    page.wait_for_selector("#fact-text")
    check("memory: the source is quoted", "lemon tree" in page.inner_text("dialog .source-quote"))
    page.fill("#fact-text", "The e2e walk keeps two lemon trees on the balcony.")
    page.click("dialog button:has-text('Save correction')")
    check("memory: correct", wait_for(lambda: page.locator("#fact-list .fact:has-text('two lemon trees')").count() == 1, 10))
    page.click("#fact-list .fact:has-text('two lemon trees') button:has-text('Pin')")
    check("memory: pin", wait_for(lambda: page.locator("#fact-list .fact:has-text('two lemon trees') .pill:has-text('Pinned')").count() == 1, 10))
    page.click("[data-memory-filter=pinned]")
    check("memory: pinned filter", wait_for(lambda: page.locator("#fact-list .fact:has-text('two lemon trees')").count() == 1, 5))
    page.click("[data-memory-filter=all]")
    page.fill("#memory-search", "lemon")
    check("memory: search", wait_for(lambda: page.locator("#fact-list .fact").count() == 1, 5))
    page.fill("#memory-search", "")
    page.wait_for_timeout(800)
    page.click("#fact-list .fact:has-text('two lemon trees') button:has-text('Source & edit')")
    page.wait_for_selector("#fact-text")
    check("memory: the earlier version is kept in the fact's history", "one other version" in page.inner_text("#dialog-body").lower())
    page.keyboard.press("Escape")
    # forgetting is a second, explicit choice, and says the conversation stays
    page.click("#fact-list .fact:has-text('two lemon trees') button:has-text('Source & edit')")
    page.wait_for_selector("#fact-text")
    page.click("dialog button:has-text('Forget this fact')")
    page.wait_for_selector("dialog button:has-text('Forget fact')")
    check("memory: forget asks a second time", "cannot be undone" in page.inner_text("#dialog-body"))
    page.click("dialog button:has-text('Keep it')")
    page.wait_for_selector("#fact-text")
    page.click("dialog button:has-text('Forget this fact')")
    page.click("dialog button:has-text('Forget fact')")
    check("memory: forget", wait_for(lambda: page.locator("#fact-list .fact:has-text('lemon')").count() == 0
                                     and not api("/api/memory/facts?q=lemon&filter=all")["facts"], 10))
    # profile, versions, restore
    before = api("/api/memory/profile")
    page.click("#profile-open")
    page.wait_for_selector("dialog .fact-flags button")
    page.click("dialog .fact-flags button")
    page.fill("#profile-text", ((before["current"] or {}).get("content", "") + "\n- Waters the lemon trees on Sundays.").strip())
    page.click("dialog button:has-text('Save')")
    check("memory: profile edited, the line is the owner's", wait_for(lambda: page.locator("dialog .profile-lines li.kept:has-text('lemon trees')").count() == 1, 10))
    if before["current"]:
        page.click(f"dialog .version:has-text('Version {before['current']['id']}') button")
        check("memory: an older profile version restored",
              wait_for(lambda: "lemon" not in (api("/api/memory/profile")["current"] or {}).get("content", ""), 10))
    else:
        api("/api/memory/profile", {"content": ""})
    page.keyboard.press("Escape")
    # export, raw log, settings, a source switch
    page.click("#memory-export")
    with page.expect_download() as d:
        page.click("dialog a:has-text('JSON')")
    check("memory: export", d.value.suggested_filename.endswith(".json"), d.value.suggested_filename)
    page.keyboard.press("Escape")
    page.click("#memory-log")
    check("memory: raw log", wait_for(lambda: page.locator("dialog .mem-log li").count() >= 1, 10))
    page.keyboard.press("Escape")
    page.click("#memory-settings")
    page.wait_for_selector("#ms-idle")
    page.click("dialog button:has-text('Save')")
    check("memory: settings saved", wait_for(lambda: not page.evaluate("document.getElementById('dialog').open"), 5))
    page.uncheck("[data-source=collect_brain]")
    off = wait_for(lambda: api("/api/memory/settings")["settings"]["collect_brain"] is False, 5)
    page.check("[data-source=collect_brain]")
    on = wait_for(lambda: api("/api/memory/settings")["settings"]["collect_brain"] is True, 5)
    check("memory: a source switched off and on", off and on)
    # forget everything: two steps, the phrase typed; cancelled here
    page.click("#memory-forget-all")
    page.click("dialog button:has-text('Continue')")
    page.wait_for_selector("#forget-phrase")
    gated = page.is_disabled("dialog button:has-text('Forget everything')")
    page.fill("#forget-phrase", "forget everything")
    check("memory: forget everything needs the typed phrase", gated and page.is_enabled("dialog button:has-text('Forget everything')"))
    page.click("dialog button:has-text('Keep my memory')")
    check("memory: forget everything cancelled", wait_for(lambda: not api("/api/memory/forget")["pending"], 5))
    page.click("#memory-notice-retry") if page.is_visible("#memory-notice-retry") else None
    ctx.close()


def states(browser):
    """The lost connection says so on every screen; nothing scrolls sideways at 320 px."""
    ctx, page = context(browser, "desktop", "night")
    seen = len(errors)
    page.goto(BASE + "/#memory")
    page.wait_for_timeout(1500)
    page.route("**/api/stream", lambda r: r.abort())
    page.reload()
    check("lost connection: the banner says so", wait_for(lambda: page.is_visible("#conn-banner"), 20))
    shot(page, "memory-offline-desktop")
    ctx.close()
    del errors[seen:]                                  # the aborted stream is this check's own doing
    ctx = browser.new_context(viewport={"width": 320, "height": 700}, device_scale_factor=2, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    page.on("pageerror", lambda e: errors.append(f"320: pageerror {e}"))
    wide = []
    for s in SCREENS:
        page.goto(f"{BASE}/#{s}")
        page.wait_for_timeout(900)
        if page.evaluate("document.documentElement.scrollWidth") > 320:
            wide.append(s)
    check("320 px: no screen scrolls sideways", not wide, ", ".join(wide))
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
    memory(b)
    states(b)
    gallery(b)
    b.close()

# what the walk taught Marvin, forgotten again (the demo's memory stays as it was)
for f in api("/api/memory/facts?filter=all&q=water%20the%20plants%20on%20Sundays&limit=50")["facts"]:
    if f["statement"].lower().startswith("i water the plants on sundays"):
        code = api("/api/memory/facts/forget", {"id": f["id"]})["confirm"]
        api("/api/memory/facts/forget", {"confirm": code})

check("no console errors", not errors, "; ".join(errors[:5]))
print(f"{sum(r[1] for r in results)}/{len(results)} passed")
sys.exit(0 if all(r[1] for r in results) else 1)

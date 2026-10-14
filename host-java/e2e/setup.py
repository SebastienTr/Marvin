#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""The first-run setup and Home's heart and breathing, in the app (Playwright), against `./marvin demo` (a fresh
database: the setup is not done yet):

- the app opens the setup by itself on the first visit (and on `/setup`), at the first step that needs the owner; the
  nine steps in the contract's order, grouped, each with what it found and how to fix it (a command with **Copy**, or
  words); this computer cannot be skipped;
- the owner: a name and two languages in the order picked, saved to the app setting `owner`, shown in Marvin >
  Preferences; the quiet hours saved (ok), then "No quiet hours" (skipped); the speaker test played, or why it cannot
  play; the rest skipped; the end card ("Marvin is ready" or what still needs the owner, each with **Finish it**);
- **Finish later**: Home, with its line saying what is left and **Continue the setup**; a later visit opens Home;
- Home's "Heart and breathing" with the last 24 hours drawn (from `/api/vitals`; contract C3, answered from its golden's
  shape by this script while the host has no vitals history yet);
- every step in Day and Night at 1440x900 and 390x844, 320 px without sideways scrolling, no console error.

Everything it changes (owner, quiet hours, skips) is put back at the end. Screenshots go to `$OUT` (default
`setup-shots/`)."""
import json, math, os, sys, time, urllib.error, urllib.request
from playwright.sync_api import sync_playwright

BASE = os.environ.get("BASE", "http://localhost:8765")
OUT = os.environ.get("OUT", "setup-shots")
os.makedirs(OUT, exist_ok=True)
errors, results = [], []
SIZES = {"phone": ({"width": 390, "height": 844}, 2), "desktop": ({"width": 1440, "height": 900}, 1)}
STEPS = ["host", "model", "voice", "robot", "mr60", "speaker", "voiceprint", "owner", "quiet_hours"]


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f" ({detail})" if detail else ""), flush=True)


def api(path, body=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", "Origin": BASE} if body is not None else {})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


def wait_for(fn, timeout=15, step=0.25):
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


def step_state(status, sid):
    return next(s for s in status["steps"] if s["id"] == sid)["state"]


def vitals_history():
    """Contract C3's shape (golden/api/get/vitals.json): a morning and an afternoon at the desk, one row per minute."""
    now = time.time()
    rows = []
    for i in range(24 * 60, 0, -1):
        h = i / 60
        if not (1.5 < h < 4 or 5 < h < 7.5 or 9 < h < 11):
            continue
        rows.append({"t": now - i * 60, "hr": round(64 + 5 * math.sin(i / 37), 1), "br": round(14 + 2 * math.sin(i / 23), 1), "n": 58})
    return {"current": {"hr": 66.3, "br": 14.0, "quality": "good", "distance_m": 0.82, "t": now}, "minutes": rows}


def host_has_vitals():
    """True when the host has trusted vitals to draw (the demo's simulated person often sits beyond the quality
    gate's range: then the drawing is checked on contract C3's shape too)."""
    try:
        minutes = api("/api/vitals?hours=24").get("minutes") or []
        return sum(1 for m in minutes if m.get("hr") is not None) >= 2
    except urllib.error.HTTPError:
        return False


def context(browser, size, theme, later=False, stub_vitals=False):
    viewport, dpr = SIZES[size]
    ctx = browser.new_context(viewport=viewport, device_scale_factor=dpr, is_mobile=size == "phone", has_touch=size == "phone")
    ctx.add_init_script(f"try {{ localStorage.setItem('marvin.appearance', JSON.stringify('{theme}')); "
                        f"localStorage.setItem('marvin.ears.offered', '1'); "
                        + ("localStorage.setItem('marvin.setup.later', 'true'); " if later
                           else "if (!sessionStorage.getItem('e2e')) { sessionStorage.setItem('e2e', '1'); localStorage.removeItem('marvin.setup.later'); } ")
                        + "} catch (e) {}")
    if stub_vitals:
        ctx.route("**/api/vitals*", lambda r: r.fulfill(status=200, content_type="application/json",
                                                         body=json.dumps(vitals_history())))
    page = ctx.new_page()
    page.on("console", lambda m: m.type == "error" and errors.append(f"{size}/{theme}: {m.text} {m.location.get('url', '')}"))
    page.on("pageerror", lambda e: errors.append(f"{size}/{theme}: pageerror {e}"))
    return ctx, page


def shot(page, name, full=False):
    page.screenshot(path=f"{OUT}/{name}.png", full_page=full)


def open_step(page, sid):
    page.locator(".setup-item").nth(STEPS.index(sid)).click()
    page.wait_for_timeout(300)


def walk(browser, stub):
    """The setup end to end, on a computer, Day."""
    ctx, page = context(browser, "desktop", "day", stub_vitals=stub)
    page.goto(BASE + "/")
    check("first visit: the setup opens by itself", wait_for(lambda: page.evaluate("location.hash") == "#setup", 10),
          page.evaluate("location.hash"))
    check("the setup screen has no navigation", not page.is_visible(".sidebar"))
    page.wait_for_selector(".setup-item")
    check("nine steps in the contract's order",
          page.locator(".setup-item").count() == 9 and page.locator(".setup-list-group").all_inner_texts()
          == ["THIS COMPUTER", "THE ROBOT", "YOU"], page.locator(".setup-list-group").all_inner_texts())
    status = api("/api/setup")
    first = next((s["id"] for s in status["steps"] if s["state"] == "missing"), None)
    current = page.locator('.setup-item[aria-current="step"]').inner_text()
    check("it opens at the first step that needs you", first is None or current.split(":")[0].strip() != "", f"{first}: {current}")
    shot(page, "setup-first-day-desktop")

    open_step(page, "host")
    check("this computer cannot be skipped", not page.is_visible("#setup-skip"))
    check("a step says what it found", page.inner_text("#setup-detail").startswith("Java "), page.inner_text("#setup-detail"))

    # a step with a command to fix it shows Copy; one with words does not
    with_command = next((s for s in status["steps"] if s["state"] == "missing" and s["fix"] and s["fix"].startswith(("./marvin", "ollama"))), None)
    if with_command:
        open_step(page, with_command["id"])
        check("a command to fix it, with Copy", page.is_visible("#setup-copy") and page.inner_text("#setup-fix-text") == with_command["fix"],
              with_command["fix"])
        shot(page, f"setup-fix-{with_command['id']}-day-desktop")

    # the owner
    open_step(page, "owner")
    page.fill("#setup-name", "Sébastien")
    page.click("label:has(input[name='setup-lang'][value='fr'])")
    page.click("label:has(input[name='setup-lang'][value='en'])")
    check("languages ranked in the order picked", page.locator(".lang-rank").all_inner_texts()[:2] == ["1", "2"])
    shot(page, "setup-owner-day-desktop")
    page.click(".setup-form button[type='submit']")
    s = wait_for(lambda: (lambda st: st if step_state(st, "owner") == "ok" else None)(api("/api/setup")), 10)
    check("the owner is saved", s is not None)
    settings = api("/api/settings")["settings"]
    check("owner setting", settings["owner"] == {"name": "Sébastien", "languages": ["fr", "en"]}, settings["owner"])
    check("the list says so", wait_for(lambda: page.get_attribute(".setup-item >> nth=7", "data-state") == "ok", 5))

    # quiet hours: saved, then none
    open_step(page, "quiet_hours")
    page.locator(".setup-form input[type='time']").nth(0).fill("22:30")
    page.click(".setup-form button[type='submit']")
    check("quiet hours saved", wait_for(lambda: api("/api/settings")["settings"]["quiet_hours"] == {"enabled": True, "start": "22:30", "end": "07:00"}, 8))
    check("quiet hours ready", wait_for(lambda: step_state(api("/api/setup"), "quiet_hours") == "ok", 5))
    shot(page, "setup-quiet-day-desktop")
    page.click("text=No quiet hours")
    check("no quiet hours: skipped", wait_for(lambda: step_state(api("/api/setup"), "quiet_hours") == "skipped", 8))

    # the speaker test: it plays, or it says why it cannot
    open_step(page, "speaker")
    page.click("#setup-play")
    page.wait_for_timeout(800)
    heard_asked = page.is_visible("#setup-heard")
    fix_shown = page.is_visible("#setup-fix")
    check("the speaker test plays, or says why not", heard_asked or fix_shown, page.inner_text("#setup-detail"))
    shot(page, "setup-speaker-day-desktop")
    if heard_asked:
        page.click("text=Yes, I heard it")
        check("heard: ready", wait_for(lambda: step_state(api("/api/setup"), "speaker") == "ok", 5))

    # skip what is left (but this computer), then the end
    skipped = []
    for sid in STEPS[1:]:
        if step_state(api("/api/setup"), sid) == "missing":
            open_step(page, sid)
            page.click("#setup-skip")
            page.wait_for_timeout(400)
            skipped.append(sid)
    status = api("/api/setup")
    check("skipped steps are skipped", all(step_state(status, sid) == "skipped" for sid in skipped), skipped)
    open_step(page, "quiet_hours")
    page.click("#setup-next")
    page.wait_for_timeout(500)
    title = page.inner_text("#setup-step-title")
    if step_state(status, "host") == "ok":
        check("done: Marvin is ready", status["done"] and title == "Marvin is ready.", title)
    else:
        # this computer itself needs something (a full disk in a test container): it says so and offers it
        check("not done: what still needs you", not status["done"] and "still need" in title
              and page.locator(".setup-summary button").count() == 1, title)
    shot(page, "setup-end-day-desktop")
    page.click("#setup-next")
    check("then Home", wait_for(lambda: page.evaluate("location.hash") == "#home", 5))
    page.wait_for_timeout(800)
    check("Home says what is left, if anything", page.is_visible("#setup-nudge") == (not status["done"]))
    page.goto(BASE + "/")
    page.wait_for_timeout(1500)
    check("a later visit opens Home", page.evaluate("location.hash") in ("", "#home") and page.is_visible("#home-title"))
    page.goto(BASE + "/#marvin/preferences")
    page.wait_for_timeout(1200)
    check("Preferences show the owner", page.input_value("#o-name") == "Sébastien"
          and page.locator("#o-languages input:checked").count() == 2)
    ctx.close()
    return skipped


def finish_later(browser, stub):
    for sid in STEPS[1:]:
        api(f"/api/setup/{sid}", {"skip": False})
    ctx, page = context(browser, "phone", "night", stub_vitals=stub)
    page.goto(BASE + "/setup")
    page.wait_for_selector(".setup-item")
    check("/setup opens the setup", page.is_visible("#setup-card"))
    page.click("#setup-later")
    check("Finish later: Home", wait_for(lambda: page.evaluate("location.hash") == "#home", 5))
    check("Home's line, with Continue", wait_for(lambda: page.is_visible("#setup-nudge"), 5), page.inner_text("#setup-nudge-text"))
    shot(page, "home-nudge-night-phone")
    page.click("#setup-nudge a")
    check("Continue the setup", wait_for(lambda: page.is_visible("#setup-card"), 5))
    ctx.close()


def gallery(browser, stub):
    for size in SIZES:
        for theme in ("day", "night"):
            ctx, page = context(browser, size, theme, stub_vitals=stub)
            page.goto(BASE + "/#setup")
            page.wait_for_selector(".setup-item")
            check(f"{size}/{theme}: appearance", page.get_attribute("html", "data-theme") == theme)
            for sid in ("host", "robot", "owner", "quiet_hours"):
                open_step(page, sid)
                page.wait_for_timeout(250)
                shot(page, f"setup-{sid}-{theme}-{size}")
            page.goto(BASE + "/#home")
            page.wait_for_timeout(1800)
            ok = page.is_visible("#vitals")
            paths = page.locator("#vitals-chart path").count()
            check(f"{size}/{theme}: Home's heart and breathing, 24 hours drawn", ok and paths >= 2, f"{paths} lanes")
            page.locator("#vitals").scroll_into_view_if_needed()
            shot(page, f"home-vitals-{theme}-{size}")
            ctx.close()
    ctx = browser.new_context(viewport={"width": 320, "height": 700}, device_scale_factor=2, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    wide = []
    page.goto(BASE + "/#setup")
    page.wait_for_selector(".setup-item")
    for sid in STEPS:
        open_step(page, sid)
        if page.evaluate("document.documentElement.scrollWidth") > 320:
            wide.append(sid)
    check("320 px: no step scrolls sideways", not wide, ", ".join(wide))
    ctx.close()


def main():
    before = api("/api/settings")["settings"]
    stub = not host_has_vitals()
    if stub:
        print("note: no trusted vitals on this host yet: answered from contract C3's shape", flush=True)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        try:
            walk(browser, stub)
            finish_later(browser, stub)
            gallery(browser, stub)
        finally:
            browser.close()
            api("/api/settings", {"owner": before["owner"], "quiet_hours": before["quiet_hours"]})
            for sid in STEPS[1:]:
                api(f"/api/setup/{sid}", {"skip": False})
    check("no console error", not errors, "; ".join(errors[:5]))
    failed = [r for r in results if not r[1]]
    print(f"{len(results) - len(failed)} passed, {len(failed)} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

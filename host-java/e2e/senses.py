#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Senses, in the app (Playwright), against a host fed by `lidar_pty.py` (a lidar on a cluttered desk, through the USB
sensor bridge) and the voice sidecar in its test mode with scripted phrases (`MARVIN_VOICE_ARGS`, see the README):

- the lidar's points are really drawn, on Senses and on Marvin > Robot (pixels of the point colour on the canvas, not
  only the rings), with the nearest return measured and the range chosen by hand;
- the microphone's level moves while the scripted phrases are spoken, and what was heard is listed with its outcome;
- the empty states say what is missing (no radar, no room sensors, no vital-sign radar, no camera, the voice off);
- Day and Night at 1440x900 and 390x844, 320 px without sideways scrolling, no console error.

Screenshots go to `$OUT` (default `senses-shots/`)."""
import json, os, sys, time, urllib.request
from playwright.sync_api import sync_playwright

BASE = os.environ.get("BASE", "http://localhost:8765")
OUT = os.environ.get("OUT", "senses-shots")
os.makedirs(OUT, exist_ok=True)
errors, results = [], []
SIZES = {"phone": ({"width": 390, "height": 844}, 2), "desktop": ({"width": 1440, "height": 900}, 1)}

# pixels of the canvas close to the appearance's point colour (--live): the scan, not the rings or the labels
POINT_PIXELS = """(id) => {
  const c = document.getElementById(id);
  if (!c || !c.width) return -1;
  const v = getComputedStyle(document.documentElement).getPropertyValue('--live').trim().replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(v.slice(i, i + 2), 16));
  const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
  let n = 0;
  for (let i = 0; i < d.length; i += 4) {
    if (d[i + 3] > 200 && Math.abs(d[i] - r) + Math.abs(d[i + 1] - g) + Math.abs(d[i + 2] - b) < 60) n++;
  }
  return n;
}"""


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f" ({detail})" if detail else ""), flush=True)


def api(path, body=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", "Origin": BASE} if body is not None else {})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


def wait_for(fn, timeout=30, step=0.3):
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
    # the appearance, and no calibration dialog over the screen
    ctx.add_init_script(f"try {{ localStorage.setItem('marvin.appearance', JSON.stringify('{theme}')); "
                        f"localStorage.setItem('marvin.ears.offered', '1'); }} catch (e) {{}}")
    page = ctx.new_page()
    page.on("console", lambda m: m.type == "error" and errors.append(f"{size}/{theme}: {m.text}"))
    page.on("pageerror", lambda e: errors.append(f"{size}/{theme}: pageerror {e}"))
    return ctx, page


def voice_restart():
    """The test mode speaks its script from the voice's start: start it again."""
    api("/api/voice/off", {})
    wait_for(lambda: api("/api/voice")["voice"]["state"] == "off", 20)
    api("/api/voice/on", {})
    return wait_for(lambda: api("/api/voice")["voice"]["state"] == "on", 60)


def lidar(page, size, theme):
    page.goto(BASE + "/#senses")
    ok = wait_for(lambda: page.evaluate(POINT_PIXELS, "s-lidar") > 150, 15)
    n = page.evaluate(POINT_PIXELS, "s-lidar")
    check(f"{size}/{theme}: Senses draws the lidar's points", ok, f"{n} point pixels, {page.inner_text('#s-lidar-meta')}")
    nearest = page.inner_text("#s-lidar-metrics")
    check(f"{size}/{theme}: nearest obstacle and scans/s", "Nearest" in nearest and "cm" in nearest and "Scans" in nearest,
          nearest.replace("\n", " ")[:80])
    check(f"{size}/{theme}: live while on screen", wait_for(lambda: page.get_attribute("#senses-live", "data-state") == "live", 10))
    for sel, what in (("#s-radar-empty", "no radar"), ("#s-room-empty", "no room sensors"), ("#s-vitals-empty", "no vital-sign radar")):
        check(f"{size}/{theme}: empty state, {what}", page.is_visible(sel), page.inner_text(sel)[:60])
    check(f"{size}/{theme}: no camera says why", page.inner_text("#s-cam-pill") in ("No camera", "Off")
          and page.is_disabled("#s-cam-take"), page.inner_text("#s-cam-line")[:70])
    page.wait_for_timeout(600)
    page.screenshot(path=f"{OUT}/senses-{theme}-{size}.png", full_page=True)
    page.goto(BASE + "/#marvin/robot")
    ok = wait_for(lambda: page.evaluate(POINT_PIXELS, "lidar") > 150, 15)
    check(f"{size}/{theme}: Marvin > Robot's lidar card draws the points", ok,
          f"{page.evaluate(POINT_PIXELS, 'lidar')} point pixels, {page.inner_text('#lidar-meta')}")
    page.locator("#lidar").scroll_into_view_if_needed()
    page.screenshot(path=f"{OUT}/robot-lidar-{theme}-{size}.png")


def ranges(page):
    page.goto(BASE + "/#senses")
    wait_for(lambda: page.evaluate(POINT_PIXELS, "s-lidar") > 150, 15)
    page.check("input[name='s-range'][value='0.5']")
    page.wait_for_timeout(500)
    check("range 0.5 m by hand", page.inner_text("#s-lidar-meta").startswith("50 cm view"), page.inner_text("#s-lidar-meta"))
    page.reload()
    page.wait_for_timeout(1500)
    check("the range is kept in this browser", page.is_checked("input[name='s-range'][value='0.5']"))
    page.check("input[name='s-range'][value='auto']")
    page.wait_for_timeout(300)


def hearing(page):
    page.goto(BASE + "/#senses")
    page.wait_for_timeout(800)
    started = voice_restart()
    check("the voice runs (test mode)", started)
    widths, dbfs = set(), []
    end = time.time() + 16
    while time.time() < end:
        widths.add(page.evaluate("() => document.getElementById('s-meter-fill').style.width"))
        dbfs.append(page.inner_text("#s-dbfs"))
        page.wait_for_timeout(120)
    loud = [float(x.split()[0]) for x in dbfs if x.endswith("dBFS") and x.split()[0] not in ("–",)]
    check("the level meter moves", len(widths) > 10 and loud and max(loud) > -35 and min(loud) < -45,
          f"{len(widths)} widths, {min(loud) if loud else '-'} to {max(loud) if loud else '-'} dBFS")
    n = page.locator("#s-utterances li").count()
    check("what was heard is listed with its outcome", n >= 1 and page.locator("#s-utterances .outcome").count() >= 1,
          page.inner_text("#s-utterances li >> nth=0").replace("\n", " ")[:90] if n else "")
    ears = page.inner_text("#s-ears")
    check("the engines and the wake word", "fake" in ears and "Marvin" in ears, ears.replace("\n", " ")[:100])
    check("the thinks strip has the last answer's stages", wait_for(lambda: page.locator("#thinks-stages .tseg").count() >= 1, 20),
          page.inner_text("#thinks-meta"))
    page.screenshot(path=f"{OUT}/senses-hears-day-desktop.png", full_page=True)
    api("/api/voice/off", {})
    ok = wait_for(lambda: page.is_visible("#s-voice-off") and "off" in page.inner_text("#s-voice-off-text"), 10)
    check("voice off: the microphone panel says why", ok, page.inner_text("#s-voice-off-text") if ok else "")
    api("/api/voice/on", {})


def narrow(browser):
    ctx = browser.new_context(viewport={"width": 320, "height": 700})
    page = ctx.new_page()
    page.goto(BASE + "/#senses")
    page.wait_for_timeout(1500)
    check("320 px: no sideways scrolling", page.evaluate("document.documentElement.scrollWidth <= 320"),
          str(page.evaluate("document.documentElement.scrollWidth")))
    ctx.close()


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for size in SIZES:
            for theme in ("day", "night"):
                ctx, page = context(browser, size, theme)
                lidar(page, size, theme)
                if size == "desktop" and theme == "day":
                    ranges(page)
                    hearing(page)
                ctx.close()
        narrow(browser)
        browser.close()
    check("no console errors", not errors, "; ".join(errors[:5]))
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

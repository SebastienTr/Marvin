#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Marvin looking through a camera, end to end (Playwright), against a running host whose voice runs in its test mode
(MARVIN_VOICE_ARGS=--fake) with stub_ollama.py as Ollama, a voice model the stub says can see (qwen3.8:27b-mlx by
default, see STUB_VISION), and ffmpeg's test pattern as the computer's camera
(MARVIN_CAMERA_INPUT="-f lavfi -i testsrc2=size=1280x720:rate=30").

For Day and Night, 1440x900 and 390x844: Marvin > Voice & models > Camera (which camera, both cameras' state, Test
camera and its picture), the camera switched off and back to Auto (the persona and the tools follow), the Overview's
camera line, then "qu'est-ce que tu vois ?" typed in Talk: the look tool called, one picture sent to the model for
images in a request of its own (checked on the stub's record of images), the answer from its description, the note
that a picture was taken, the inspector's look call; and no sideways scrolling. Screenshots go to $OUT (default
camera-shots/); it fails on any console error.

    python3 host-java/e2e/camera.py [BASE_URL]
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8765"
STUB = os.environ.get("STUB_URL", "http://127.0.0.1:11434")
OUT = os.environ.get("OUT", "camera-shots")
SIZES = {"desktop": ({"width": 1440, "height": 900}, 1), "phone": ({"width": 390, "height": 844}, 2)}
errors: list[str] = []
failed: list[str] = []


def check(what, ok):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        failed.append(what)


def get(url):
    with urllib.request.urlopen(url, timeout=10) as r:
        return json.load(r)


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def voice_on():
    for _ in range(60):
        if get(BASE + "/api/voice")["voice"]["state"] == "on":
            return True
        time.sleep(0.5)
    return False


def stub_images():
    return get(STUB + "/stub/images")["images"]


def context(browser, size, theme):
    viewport, dpr = SIZES[size]
    ctx = browser.new_context(viewport=viewport, device_scale_factor=dpr, is_mobile=size == "phone", has_touch=size == "phone")
    ctx.add_init_script(f"try {{ localStorage.setItem('marvin.appearance', JSON.stringify('{theme}')); }} catch (e) {{}}")
    page = ctx.new_page()
    page.on("console", lambda m: m.type == "error" and errors.append(f"{size}/{theme}: {m.text}"))
    page.on("pageerror", lambda e: errors.append(f"{size}/{theme}: pageerror {e}"))
    return ctx, page


def until(page, js, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        if page.evaluate(f"() => !!({js})"):
            return True
        time.sleep(0.1)
    raise TimeoutError(js)


def shot(page, name):
    page.screenshot(path=f"{OUT}/{name}.png", full_page=False)


def no_sideways(page, what):
    check(f"{what}: no sideways scrolling",
          page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"))


def run(browser, size, theme):
    tag = f"{theme}-{size}"
    ctx, page = context(browser, size, theme)
    page.goto(BASE + "/#marvin/voice")
    page.wait_for_selector("#camera-form")
    check(f"{tag}: appearance", page.get_attribute("html", "data-theme") == theme)
    until(page, "document.getElementById('camera-pill').textContent !== '–'")
    pill = page.inner_text("#camera-pill")
    check(f"{tag}: the computer's camera is used ({pill})", pill == "This computer (auto)")
    check(f"{tag}: Auto is chosen", page.is_checked("input[name=cam][value=auto]"))
    sources = page.inner_text("#camera-sources")
    check(f"{tag}: both cameras and their state", "This computer" in sources and "Ready" in sources
          and "No robot with a camera is connected" in sources)
    page.locator("#camera-form").scroll_into_view_if_needed()
    page.click("#camera-test")
    page.wait_for_selector("#camera-shot:not([hidden])")
    until(page, "document.getElementById('camera-img').naturalWidth > 0")
    meta = page.inner_text("#camera-meta")
    check(f"{tag}: the test shows the picture ({meta})", "1280 × 720" in meta and "not kept" in meta
          and page.evaluate("document.getElementById('camera-img').naturalWidth") == 1280)
    page.locator("#camera-form").scroll_into_view_if_needed()
    page.wait_for_timeout(300)
    shot(page, f"{tag}-1-camera")
    no_sideways(page, tag)

    if (size, theme) in (("desktop", "day"), ("phone", "night")):
        page.check("input[name=cam][value=off]")
        page.click("#camera-form button[type=submit]")
        until(page, "document.getElementById('camera-pill').textContent === 'Off'")
        check(f"{tag}: off: no test", page.is_disabled("#camera-test"))
        check(f"{tag}: off in the API", get(BASE + "/api/camera")["source"] is None)
        check(f"{tag}: voice back on", voice_on())
        shot(page, f"{tag}-2-off")
        page.check("input[name=cam][value=auto]")
        page.click("#camera-form button[type=submit]")
        until(page, "document.getElementById('camera-pill').textContent === 'This computer (auto)'")
        check(f"{tag}: voice back on", voice_on())

    page.goto(BASE + "/#marvin")
    until(page, "document.getElementById('camera-line').textContent.includes('Looks only when you ask')")
    check(f"{tag}: the Overview names the camera", "This computer's camera" in page.inner_text("#camera-line"))

    page.goto(BASE + "/#talk")
    page.wait_for_selector("#ask-input:not([disabled])")
    before = len(stub_images())
    replies = page.locator("#transcript > li.msg.marvin").count()
    page.fill("#ask-input", "Marvin, qu'est-ce que tu vois ?")
    page.keyboard.press("Enter")
    until(page, f"document.querySelectorAll('#transcript > li.msg.marvin:not(.live)').length > {replies}", 40)
    until(page, "document.getElementById('toast').textContent.includes('Marvin took a picture')", 10)
    toast = page.inner_text("#toast")
    check(f"{tag}: a note says a picture was taken ({toast})", "this computer’s camera" in toast)
    answer = page.locator("#transcript > li.msg.marvin").last.inner_text()
    check(f"{tag}: the answer comes from the description ({answer[:80]})", "test pattern" in answer)
    shown = stub_images()[before:]
    check(f"{tag}: one picture, to the model for images, in a request of its own",
          len(shown) == 1 and shown[0]["model"] == "qwen3.8:27b-mlx" and shown[0]["message"] == 1
          and shown[0]["last_user"])
    shot(page, f"{tag}-3-looked")
    page.locator("#transcript > li.msg.marvin .insp-btn").last.click()
    page.wait_for_selector("#dialog[open]")
    text = page.inner_text("#dialog")
    check(f"{tag}: the inspector shows the look call and what was seen", "look" in text and "test pattern" in text.lower())
    shot(page, f"{tag}-4-inspector")
    page.keyboard.press("Escape")
    no_sideways(page, tag)
    ctx.close()


def main():
    os.makedirs(OUT, exist_ok=True)
    check("the voice is on", voice_on() or bool(post("/api/voice/on", {})) and voice_on())
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for size in ("desktop", "phone"):
            for theme in ("day", "night"):
                run(browser, size, theme)
        browser.close()
    for e in errors:
        print("console error: " + e)
    print(f"{len(failed)} failed, {len(errors)} console errors; screenshots in {OUT}")
    sys.exit(1 if failed or errors else 0)


if __name__ == "__main__":
    main()

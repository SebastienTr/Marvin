#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Showing Marvin an image, end to end in the app (Playwright), against a running host whose voice runs in its test
mode (MARVIN_VOICE_ARGS=--fake) with stub_ollama.py as Ollama and a voice model the stub says can see
(qwen3.8:27b-mlx by default, see STUB_VISION).

For Day and Night, 1440x900 and 390x844: the image picked (on a phone through the camera-or-library menu), its
preview, the typed question and its bubble with the thumbnail, the inspector's "Image sent", a follow-up question
that does not send the image again (checked on the stub's record of images), a model that cannot see (refused, with
the way to fix it), dropping and pasting an image (desktop), and no sideways scrolling. Screenshots go to $OUT
(default vision-shots/); it fails on any console error.

    python3 host-java/e2e/vision.py [BASE_URL]
"""
from __future__ import annotations

import json
import os
import struct
import sys
import tempfile
import time
import urllib.request
import zlib

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8765"
STUB = os.environ.get("STUB_URL", "http://127.0.0.1:11434")
OUT = os.environ.get("OUT", "vision-shots")
SIZES = {"desktop": ({"width": 1440, "height": 900}, 1), "phone": ({"width": 390, "height": 844}, 2)}
errors: list[str] = []
failed: list[str] = []


def check(what, ok):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        failed.append(what)


def card(path, w=1600, h=1000):
    """A test card (a PNG, no library needed): a red square, a blue disc, a green band."""
    rows = []
    for y in range(h):
        row = bytearray(b"\x00")
        for x in range(w):
            if 200 <= x < 700 and 200 <= y < 700:
                px = (200, 65, 44)
            elif (x - 1100) ** 2 + (y - 450) ** 2 < 260 ** 2:
                px = (44, 107, 200)
            elif 800 <= y < 880:
                px = (61, 154, 74)
            else:
                px = (244, 239, 226)
            row += bytes(px)
        rows.append(bytes(row))
    raw = zlib.compress(b"".join(rows), 6)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"tEXt", b"Comment\x00a test card") + chunk(b"IDAT", raw) + chunk(b"IEND", b""))


def get(url):
    with urllib.request.urlopen(url, timeout=10) as r:
        return json.load(r)


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
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
    # the refused image is a 422 on purpose: the browser logs it, nothing else may
    page.on("console", lambda m: m.type == "error" and "status of 422" not in m.text
            and errors.append(f"{size}/{theme}: {m.text}"))
    page.on("pageerror", lambda e: errors.append(f"{size}/{theme}: pageerror {e}"))
    return ctx, page


def until(page, js, seconds=20):
    """Waits for a JavaScript expression (polled from here: the page's content policy forbids eval)."""
    end = time.time() + seconds
    while time.time() < end:
        if page.evaluate(f"() => !!({js})"):
            return True
        time.sleep(0.1)
    raise TimeoutError(js)


def shot(page, name):
    page.screenshot(path=f"{OUT}/{name}.png", full_page=False)


def pick(page, size, image):
    """The image button: the file picker on a desktop, the camera-or-library menu on a phone."""
    if size == "phone":
        page.click("#attach-button")
        page.wait_for_selector("#attach-menu:not([hidden])")
        with page.expect_file_chooser() as fc:
            page.click("#attach-library")
    else:
        with page.expect_file_chooser() as fc:
            page.click("#attach-button")
    fc.value.set_files(image)


def no_sideways(page, what):
    check(f"{what}: no sideways scrolling",
          page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"))


def run(browser, size, theme, image):
    tag = f"{theme}-{size}"
    ctx, page = context(browser, size, theme)
    page.goto(BASE + "/#talk")
    page.wait_for_selector("#ask-input:not([disabled])")
    check(f"{tag}: appearance", page.get_attribute("html", "data-theme") == theme)
    post("/api/voice/image/remove", {})
    before = len(stub_images())

    if size == "phone":
        page.click("#attach-button")
        page.wait_for_selector("#attach-menu:not([hidden])")
        check(f"{tag}: the phone offers the camera or the library",
              page.inner_text("#attach-camera") == "Take a photo" and page.inner_text("#attach-library") == "Choose a photo")
        check(f"{tag}: the camera input asks for the camera", page.get_attribute("#attach-capture", "capture") == "environment")
        shot(page, f"{tag}-1-menu")
        page.keyboard.press("Escape")
    pick(page, size, image)
    page.wait_for_selector("#attach[data-state=ready]")
    note = page.inner_text("#attach-note")
    meta = page.inner_text("#attach-meta")
    check(f"{tag}: the preview says it goes with the next question", note == "Goes with your next question, typed or spoken.")
    check(f"{tag}: downscaled to 1280 before sending ({meta})", meta.startswith("1280 × 800 ·") and "qwen3.8:27b-mlx" in meta)
    check(f"{tag}: the composer invites a question about it", page.get_attribute("#ask-input", "placeholder") == "Ask about the image…")
    shot(page, f"{tag}-2-attached")
    no_sideways(page, tag)

    replies = page.locator("#transcript > li.msg.marvin").count()
    page.fill("#ask-input", "What is on this card?")
    page.keyboard.press("Enter")
    until(page, f"document.querySelectorAll('#transcript > li.msg.marvin:not(.live)').length > {replies}")
    page.wait_for_selector("#attach", state="hidden")
    you = page.locator("#transcript > li.msg.you").last
    check(f"{tag}: the question's bubble shows the image", you.locator(".msg-image img").count() == 1)
    shown = stub_images()[before:]
    check(f"{tag}: one image reached the model, on the question itself",
          len(shown) == 1 and shown[0]["last_user"] and shown[0]["model"] == "qwen3.8:27b-mlx")
    check(f"{tag}: 1280 x 800 JPEG", shown and shown[0]["bytes"] < 300_000)
    page.wait_for_timeout(700)
    shot(page, f"{tag}-3-answered")

    page.locator("#transcript > li.msg.marvin .insp-btn").last.click()
    page.wait_for_selector("#dialog[open]")
    text = page.inner_text("#dialog")
    check(f"{tag}: the inspector says which image and which model",
          "image sent" in text.lower() and "1280 × 800 pixels" in text and "Seen by qwen3.8:27b-mlx" in text)
    page.locator("#dialog .insp-image").scroll_into_view_if_needed()
    shot(page, f"{tag}-4-inspector")
    page.keyboard.press("Escape")

    replies = page.locator("#transcript > li.msg.marvin").count()
    page.fill("#ask-input", "And the disc, what colour is it?")
    page.keyboard.press("Enter")
    until(page, f"document.querySelectorAll('#transcript > li.msg.marvin:not(.live)').length > {replies}")
    check(f"{tag}: the next question does not send the image again", len(stub_images()) == before + 1)

    if (size, theme) in (("desktop", "day"), ("phone", "night")):
        post("/api/voice/settings", {"vision_model": "qwen3:4b-instruct"})
        page.wait_for_timeout(500)
        check(f"{tag}: voice back on", voice_on())
        page.wait_for_selector("#ask-input:not([disabled])")
        pick(page, size, image)
        page.wait_for_selector("#attach-error:not([hidden])")
        err = page.inner_text("#attach-error")
        check(f"{tag}: a model that cannot see is refused, with the fix ({err})",
              err.startswith("qwen3:4b-instruct cannot see images; choose a vision model in Marvin > Voice")
              and page.locator("#attach-error a[href='#marvin/voice']").count() == 1)
        check(f"{tag}: nothing waits after a refusal", page.is_hidden("#attach"))
        shot(page, f"{tag}-5-refused")
        page.click("#attach-error a")
        page.wait_for_selector("#v-vision")
        until(page, "document.getElementById('v-vision-hint').textContent.includes('cannot see images')")
        page.locator("#v-vision").scroll_into_view_if_needed()
        shot(page, f"{tag}-6-settings")
        post("/api/voice/settings", {"vision_model": ""})
        check(f"{tag}: voice back on", voice_on())

    if size == "desktop":
        page.goto(BASE + "/#talk")
        page.wait_for_selector("#ask-input:not([disabled])")
        data = open(image, "rb").read()
        page.evaluate("""([bytes]) => {
          const f = new File([new Uint8Array(bytes)], 'card.png', { type: 'image/png' });
          const dt = new DataTransfer(); dt.items.add(f);
          const panel = document.querySelector('.conversation');
          panel.dispatchEvent(new DragEvent('dragenter', { dataTransfer: dt, bubbles: true, cancelable: true }));
          window.__dt = dt;
        }""", [list(data)])
        check(f"{tag}: dropping shows where", page.is_visible("#drop-hint"))
        shot(page, f"{tag}-7-drop")
        page.evaluate("""() => document.querySelector('.conversation').dispatchEvent(
          new DragEvent('drop', { dataTransfer: window.__dt, bubbles: true, cancelable: true }))""")
        page.wait_for_selector("#attach[data-state=ready]")
        check(f"{tag}: a dropped image waits", page.is_hidden("#drop-hint"))
        page.click("#attach-remove")
        page.wait_for_selector("#attach", state="hidden")
        check(f"{tag}: removed on the host too", "image" not in get(BASE + "/api/voice")["voice"])
        page.evaluate("""([bytes]) => {
          const f = new File([new Uint8Array(bytes)], 'card.png', { type: 'image/png' });
          const dt = new DataTransfer(); dt.items.add(f);
          document.getElementById('ask-input').dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
        }""", [list(data)])
        page.wait_for_selector("#attach[data-state=ready]")
        check(f"{tag}: a pasted image waits", "image" in get(BASE + "/api/voice")["voice"])
        page.click("#attach-remove")
        page.wait_for_selector("#attach", state="hidden")
    ctx.close()


def main():
    os.makedirs(OUT, exist_ok=True)
    check("the voice is on", voice_on() or bool(post("/api/voice/on", {})) and voice_on())
    image = os.path.join(tempfile.mkdtemp(), "card.png")
    card(image)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for size in ("desktop", "phone"):
            for theme in ("day", "night"):
                run(browser, size, theme, image)
        browser.close()
    for e in errors:
        print("console error: " + e)
    print(f"{len(failed)} failed, {len(errors)} console errors; screenshots in {OUT}")
    sys.exit(1 if failed or errors else 0)


if __name__ == "__main__":
    main()

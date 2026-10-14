#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Background tasks, end to end (Playwright), against a running host whose voice runs in its test mode
(MARVIN_VOICE_ARGS=--fake) with stub_ollama.py as Ollama and a Qwen3.8 model as llm_model (the stub says it thinks,
with Ollama's levels false, low, medium, xhigh).

For Day and Night, 1440x900 and 390x844: Activity > Tasks: a task typed in the form runs live (what it is doing, its
steps used, Cancel), ends with Marvin's summary, its result in Markdown (a table) and its steps with the model's
reasoning in the inspector; a slow one is cancelled. Once: a question typed in Talk that asks for work in several steps
starts a task (the model's start_task; Marvin answers "Je m'en occupe" without a second request), the task pauses while
a conversation goes on, then Marvin says it is ready on its own; "c'est quoi le résultat ?" gets the summary through
task_result; Marvin > Voice & models > Thinking & tasks shows the model's size and levels (tasks sent as xhigh) and
saves a level without restarting the voice. No sideways scrolling; screenshots go to $OUT (default tasks-shots/); it
fails on any console error.

    python3 host-java/e2e/tasks.py [BASE_URL]
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

# the app opens the first-run setup on a fresh host: these checks start from Home
SETUP_LATER = "try { localStorage.setItem(\'marvin.setup.later\', \'true\'); } catch (e) {}"

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8765"
STUB_LOG = os.environ.get("STUB_LOG", "")
OUT = os.environ.get("OUT", "tasks-shots")
SIZES = {"desktop": ({"width": 1440, "height": 900}, 1), "phone": ({"width": 390, "height": 844}, 2)}
RUN = time.strftime("%H%M%S")          # a run's own titles: the list keeps earlier runs' tasks
errors: list[str] = []
failed: list[str] = []


def check(what, ok):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        failed.append(what)


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=10) as r:
        return json.load(r)


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def voice_on():
    for _ in range(60):
        if get("/api/voice")["voice"]["state"] == "on":
            return True
        time.sleep(0.5)
    return False


def wait(cond, seconds=30):
    end = time.time() + seconds
    while time.time() < end:
        v = cond()
        if v:
            return v
        time.sleep(0.2)
    return None


def context(browser, size, theme):
    viewport, dpr = SIZES[size]
    ctx = browser.new_context(viewport=viewport, device_scale_factor=dpr, is_mobile=size == "phone", has_touch=size == "phone")
    ctx.add_init_script(SETUP_LATER)  # the first-run setup is setup.py's
    ctx.add_init_script(f"try {{ localStorage.setItem('marvin.appearance', JSON.stringify('{theme}')); }} catch (e) {{}}")
    page = ctx.new_page()
    page.on("console", lambda m: m.type == "error" and errors.append(f"{size}/{theme}: {m.text}"))
    page.on("pageerror", lambda e: errors.append(f"{size}/{theme}: pageerror {e}"))
    return ctx, page


def until(page, js, seconds=30):
    end = time.time() + seconds
    while time.time() < end:
        if page.evaluate(f"() => !!({js})"):
            return True
        time.sleep(0.1)
    raise TimeoutError(js)


def shot(page, name):
    page.screenshot(path=f"{OUT}/{name}.png", full_page=False)


def no_sideways(page, what):
    check(f"{what}: no sideways scrolling", page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"))


def task_li(title):
    return f"[...document.querySelectorAll('#task-list li.task')].find(li => li.querySelector('h3').textContent === {json.dumps(title)})"


def run(browser, size, theme):
    tag = f"{theme}-{size}"
    ctx, page = context(browser, size, theme)
    page.goto(BASE + "/#activity")
    page.wait_for_selector("#task-form")
    check(f"{tag}: appearance", page.get_attribute("html", "data-theme") == theme)

    # a task typed in the form: live, then done with its summary, its result and its steps
    goal = f"Compare two travel laptops ({tag}, {RUN})"
    page.fill("#task-goal", goal)
    page.click("#task-form button[type=submit]")
    until(page, task_li(goal))
    until(page, f"{task_li(goal)}.classList.contains('running')", 20)
    doing = page.evaluate(f"{task_li(goal)}.querySelector('.task-doing').textContent")
    check(f"{tag}: it says what it is doing ({doing})", bool(doing))
    check(f"{tag}: the title says it works", "Working on" in page.inner_text("#tasks-title"))
    page.locator("#task-list li.task.running").first.scroll_into_view_if_needed()
    shot(page, f"{tag}-1-running")
    until(page, f"{task_li(goal)}.classList.contains('done')", 40)
    li = page.locator("#task-list li.task.done", has_text=goal).first
    check(f"{tag}: Marvin's summary", "Option A is cheaper" in li.inner_text())
    li.get_by_role("button", name="Read the result").click()
    until(page, f"{task_li(goal)}.querySelector('.task-result table')")
    cells = page.evaluate(f"[...{task_li(goal)}.querySelectorAll('.task-result td')].map(c => c.textContent)")
    check(f"{tag}: the result's table ({cells[:3]})", cells[:3] == ["A", "10 €", "cheaper"])
    check(f"{tag}: the result's bold is bold", page.evaluate(f"!!{task_li(goal)}.querySelector('.task-result td strong')"))
    li.scroll_into_view_if_needed()
    shot(page, f"{tag}-2-result")
    no_sideways(page, tag)
    li.get_by_role("button", name="Steps & reasoning").click()
    page.wait_for_selector("#dialog[open] .tstep")
    until(page, "document.querySelectorAll('#task-steps .tstep').length >= 4")
    text = page.inner_text("#dialog")
    check(f"{tag}: the steps: the model, recall, the final answer", "recall(" in text and "final_answer" in text)
    check(f"{tag}: thinking as sent (high: xhigh)", "sent as xhigh" in text)
    page.locator("#dialog .tstep-thinking summary").first.click()
    check(f"{tag}: the model's reasoning, folded", "The owner wants" in page.inner_text("#dialog .tstep-thinking pre >> nth=0"))
    shot(page, f"{tag}-3-steps")
    page.keyboard.press("Escape")

    # a slow one, cancelled
    if (size, theme) in (("desktop", "day"), ("phone", "night")):
        slow = f"A slow comparison ({tag}, {RUN})"
        page.fill("#task-goal", slow)
        page.click("#task-form button[type=submit]")
        # cancelled while the model thinks (its request open), not while it waits for the conversation to end
        until(page, f"{task_li(slow)}?.querySelector('.task-doing')?.textContent.startsWith('Thinking (')", 30)
        page.locator("#task-list li.task.running", has_text=slow).get_by_role("button", name="Cancel").click()
        until(page, f"{task_li(slow)}.classList.contains('cancelled')", 10)
        check(f"{tag}: cancelled", True)
        if STUB_LOG:
            check(f"{tag}: Ollama's request was dropped", "CLIENT GONE" in open(STUB_LOG).read())
    no_sideways(page, tag)
    ctx.close()


def conversation(browser):
    """Once: tasks from the conversation, the announcement, the result asked for, the settings."""
    ctx, page = context(browser, "desktop", "day")
    page.goto(BASE + "/#talk")
    page.wait_for_selector("#ask-input:not([disabled])")
    replies = page.locator("#transcript > li.msg.marvin").count()
    page.fill("#ask-input", "Prépare-moi une comparaison des forfaits mobiles")
    page.keyboard.press("Enter")
    until(page, f"document.querySelectorAll('#transcript > li.msg.marvin:not(.live)').length > {replies}", 30)
    answer = page.locator("#transcript > li.msg.marvin").last.inner_text()
    check(f"the model started a task and Marvin said so ({answer[:60]})", "Je m'en occupe" in answer)
    task = wait(lambda: next((t for t in get("/api/tasks")["tasks"] if t["goal"].startswith("Prépare-moi")), None))
    check("the task, typed in Talk, in French", bool(task) and task["origin"] == "typed" and task["language"] == "fr")
    done = wait(lambda: next((t for t in get("/api/tasks")["tasks"] if t["id"] == task["id"] and t["status"] == "done"), None), 60)
    check("done", bool(done))
    said = wait(lambda: any(e["kind"] == "reply" and e.get("proactive") and "C'est prêt" in e["text"]
                            for e in get("/api/voice")["transcript"]), 20)
    check("Marvin said it was ready, on its own", bool(said))
    shot(page, "conversation-1-ready")
    replies = page.locator("#transcript > li.msg.marvin:not(.live)").count()
    page.fill("#ask-input", "C'est quoi le résultat ?")
    page.keyboard.press("Enter")
    until(page, f"document.querySelectorAll('#transcript > li.msg.marvin:not(.live)').length > {replies}", 30)
    answer = page.locator("#transcript > li.msg.marvin").last.inner_text()
    check(f"the result, asked for ({answer[:70]})", "Option A is cheaper" in answer)
    shot(page, "conversation-2-result")

    page.goto(BASE + "/#marvin/voice")
    page.wait_for_selector("#reasoning-form")
    until(page, "!document.getElementById('reasoning-fields').disabled")
    card = page.inner_text("#reasoning-form")
    check("the model's size and levels", "32 GB" in card and "low, medium, xhigh" in card and "xhigh" in card)
    check("off when talking, high on tasks", page.is_checked("input[name=r-chat][value=off]")
          and page.is_checked("input[name=r-task][value=high]"))
    page.locator("#reasoning-form").scroll_into_view_if_needed()
    shot(page, "conversation-3-settings")
    page.check("input[name=r-chat][value=low]")
    page.click("#reasoning-form button[type=submit]")
    until(page, "document.getElementById('reasoning-saved').textContent === 'Saved'")
    check("saved, the voice still on", get("/api/tasks/settings")["settings"]["reasoning"] == "low"
          and get("/api/voice")["voice"]["state"] == "on")
    post("/api/tasks/settings", {"reasoning": "off"})
    ctx.close()


def main():
    os.makedirs(OUT, exist_ok=True)
    check("the voice is on", voice_on() or bool(post("/api/voice/on", {})) and voice_on())
    for t in get("/api/tasks?limit=100")["tasks"]:          # an earlier run's, still waiting or running
        if t["status"] in ("queued", "running"):
            post("/api/tasks/cancel", {"id": t["id"]})
    post("/api/tasks/settings", {"reasoning": "off", "task_reasoning": "high", "tasks": True})
    wait(lambda: not get("/api/tasks?limit=100")["open"], 20)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        conversation(browser)
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

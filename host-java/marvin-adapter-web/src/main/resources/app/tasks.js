// SPDX-License-Identifier: MIT
// Background tasks (docs/ui.md, Activity): what Marvin is working on while you talk, live (its steps, what it is doing,
// its budget, Cancel), and what it found (the summary it says, the result in Markdown, every step with the model's
// reasoning in the inspector); a form to give it one. And, in Marvin > Voice & models, how hard the one model thinks
// when you talk and when it works in the background.

import { $, app, el, api, post, on, fmtTime, whenLabel, plural, toast, openDialog } from "./core.js";
import { renderMarkdown } from "./markdown.js";

const STATUS = {
  queued: "Waiting", running: "Working", done: "Done", failed: "Did not finish", cancelled: "Cancelled",
  interrupted: "Interrupted",
};
const ORIGIN = { voice: "asked aloud", typed: "typed in Talk", app: "started here" };
const LEVELS = { off: "Off", low: "Low", medium: "Medium", high: "High" };
const SHOWN = 8;

const tasks = new Map();             // id -> the task as lists show it
const results = new Map();           // id -> the task with its result and steps, once read
let showAll = false;
let inspected = null;               // the task whose steps the dialog shows
let failed = "";
let ticker = 0;

// ------------------------------------------------------------------ the list

function sorted() {
  return [...tasks.values()].sort((a, b) => b.created_at - a.created_at);
}

/** "4 min", "45 s", "1 h 05". */
function span(s) {
  s = Math.max(0, Math.round(s || 0));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")}`;
}

const nowS = () => Date.now() / 1000;

function when(t) {
  if (t.status === "running") return `started ${fmtTime(t.started_at)} · ${span(nowS() - t.started_at)}`;
  if (t.status === "queued") return `asked ${fmtTime(t.created_at)}`;
  const took = t.started_at && t.finished_at ? ` · took ${span(t.finished_at - t.started_at)}` : "";
  return `${whenLabel(t.finished_at || t.created_at)}${took}`;
}

function statusPill(t) {
  const p = el("span", `pill task-pill ${t.status}`, STATUS[t.status] || t.status);
  if (t.status === "failed" || t.status === "interrupted") p.classList.add("attention-pill");
  return p;
}

function budgetLine(t) {
  const s = t.spent || {}, b = t.budget || {};
  const parts = [`step ${s.steps || 0} of ${b.steps}`];
  if (s.tool_calls) parts.push(plural(s.tool_calls, "tool used", "tools used"));
  if (s.tokens) parts.push(`${s.tokens.toLocaleString("en-GB")} of ${(b.tokens || 0).toLocaleString("en-GB")} tokens`);
  return parts.join(" · ");
}

function button(text, cls, fn) {
  const b = el("button", cls, text);
  b.type = "button";
  b.addEventListener("click", fn);
  return b;
}

function taskItem(t) {
  const li = el("li", `task ${t.status}`);
  li.dataset.id = t.id;
  const head = el("div", "task-head");
  const title = el("h3", null, t.title);
  title.title = t.goal;
  head.append(title, statusPill(t));
  li.append(head);
  const meta = el("p", "task-meta small muted");
  meta.append(el("span", "task-when", when(t)), ` · ${ORIGIN[t.origin] || t.origin}`);
  li.append(meta);
  if (t.status === "running" || t.status === "queued") {
    li.append(el("p", "task-doing", t.activity || (t.status === "queued" ? "Waiting its turn" : "Working")));
    const track = el("div", "track task-track");
    track.setAttribute("role", "progressbar");
    track.setAttribute("aria-label", "Steps used");
    const used = Math.min(1, ((t.spent || {}).steps || 0) / ((t.budget || {}).steps || 1));
    track.setAttribute("aria-valuenow", String(Math.round(used * 100)));
    const bar = el("i");
    bar.style.width = `${Math.max(3, used * 100)}%`;
    track.append(bar);
    li.append(track, el("p", "small muted", budgetLine(t)));
  } else {
    if (t.summary) li.append(el("p", "task-summary", t.summary));
    if (t.error && t.status !== "done") li.append(el("p", "task-error small", t.error));
  }
  const actions = el("div", "task-actions");
  if (t.status === "running" || t.status === "queued") {
    actions.append(button("Cancel", "small-button", () => cancel(t)));
  }
  if (t.has_result) {
    const open = button("Read the result", "small-button", () => toggleResult(t, li, open));
    open.setAttribute("aria-expanded", "false");
    actions.append(open);
  }
  if (t.status !== "queued") actions.append(button("Steps & reasoning", "small-button quiet", () => inspect(t.id)));
  li.append(actions);
  return li;
}

function render() {
  const list = $("task-list");
  if (!list) return;
  const all = sorted();
  const open = all.filter((t) => t.status === "running" || t.status === "queued");
  const pill = $("tasks-pill");
  pill.textContent = failed ? "Unavailable" : open.length ? `${open.length} in motion` : "Idle";
  pill.className = `pill${open.length ? " attention-pill" : ""}`;
  $("tasks-title").textContent = open.length ? (open.length === 1 ? "Working on one thing." : `Working on ${open.length} things.`)
    : all.length ? "Nothing in motion." : "Nothing yet.";
  const shown = showAll ? all : all.slice(0, SHOWN);
  // keep an open result open while the list changes
  const opened = new Set([...list.querySelectorAll(".task.result-open")].map((x) => x.dataset.id));
  list.replaceChildren(...shown.map((t) => {
    const li = taskItem(t);
    if (opened.has(t.id) && results.has(t.id)) showResult(t, li, li.querySelector(".task-actions button[aria-expanded]"));
    return li;
  }));
  $("tasks-empty").hidden = all.length > 0 || !!failed;
  $("tasks-failed").hidden = !failed;
  if (failed) $("tasks-failed-text").textContent = `Tasks cannot be read: ${failed}`;
  const more = $("tasks-more");
  more.hidden = showAll || all.length <= SHOWN;
  more.textContent = `Show all ${all.length}`;
  clearInterval(ticker);
  if (open.some((t) => t.status === "running")) {
    ticker = setInterval(() => {
      if (app.view !== "activity") return;
      for (const t of open) {
        const w = list.querySelector(`[data-id="${t.id}"] .task-when`);
        if (w) w.textContent = when(tasks.get(t.id) || t);
      }
    }, 1000);
  }
}

async function full(id) {
  const r = await api(`/api/tasks/${encodeURIComponent(id)}`);
  results.set(id, r);
  return r;
}

function showResult(t, li, btn) {
  const r = results.get(t.id);
  if (!r) return;
  let box = li.querySelector(".task-result");
  if (!box) {
    box = el("div", "task-result md");
    li.querySelector(".task-actions").before(box);
  }
  box.replaceChildren(renderMarkdown(r.task.result));
  li.classList.add("result-open");
  if (btn) { btn.textContent = "Hide the result"; btn.setAttribute("aria-expanded", "true"); }
}

async function toggleResult(t, li, btn) {
  const box = li.querySelector(".task-result");
  if (box) {
    box.remove();
    li.classList.remove("result-open");
    btn.textContent = "Read the result";
    btn.setAttribute("aria-expanded", "false");
    return;
  }
  try {
    if (!results.has(t.id) || results.get(t.id).task.status !== t.status) await full(t.id);
    showResult(t, li, btn);
  } catch (e) {
    toast(`The result cannot be read: ${e.message}`);
  }
}

async function cancel(t) {
  try {
    const r = await post("/api/tasks/cancel", { id: t.id });
    upsert(r.task);
    toast(t.status === "queued" ? "Cancelled." : "Stopping…");
  } catch (e) {
    toast(`Not cancelled: ${e.message}`);
  }
}

function upsert(t) {
  const before = tasks.get(t.id);
  tasks.set(t.id, t);
  if (before && before.status !== t.status) {
    results.delete(t.id);
    if (t.status === "done") toast(`Task done: ${t.title}`);
    else if (t.status === "failed") toast(`Task did not finish: ${t.title}`);
  }
  render();
  if (inspected === t.id) inspectorHead(t);
}

export async function load() {
  try {
    const r = await api("/api/tasks?limit=30");
    failed = "";
    tasks.clear();
    for (const t of r.tasks) tasks.set(t.id, t);
  } catch (e) {
    failed = e.message;
  }
  render();
}

// ------------------------------------------------------------------ the inspector: every step, its reasoning

function toolText(name, args) {
  const a = Object.entries(args || {}).map(([k, v]) => `${k}: ${JSON.stringify(v)}`);
  return `${name}(${a.join(", ")})`;
}

function stepItem(s) {
  const li = el("li", `tstep ${s.kind}`);
  li.dataset.n = s.n;
  const head = el("div", "tstep-head");
  head.append(el("span", "tstep-n", String(s.n)), el("span", "tstep-kind",
    s.kind === "model" ? "The model" : s.kind === "tool" ? "A tool" : "Note"), el("span", "tstep-at small muted", fmtTime(s.at)));
  if (s.seconds != null) head.append(el("span", "tstep-dur small muted", `${s.seconds < 10 ? s.seconds.toFixed(1) : Math.round(s.seconds)} s`));
  li.append(head);
  if (s.kind === "model") {
    if (s.thinking) {
      const d = el("details", "tstep-thinking");
      const words = s.thinking.split(/\s+/).length;
      d.append(el("summary", null, `Reasoning (${plural(words, "word", "words")})`), el("pre", null, s.thinking));
      li.append(d);
    }
    if (s.text) li.append(el("p", "tstep-text", s.text));
    for (const c of s.tool_calls || []) li.append(el("code", "tstep-call", `→ ${toolText(c.name, c.arguments)}`));
    if (s.usage) {
      const u = s.usage;
      li.append(el("p", "small muted", `${u.eval_count} tokens written in ${u.eval_s} s · ${u.prompt_eval_count} prompt tokens read`));
    }
    if (s.last) li.append(el("p", "small muted", "Asked to finish: the budget was nearly spent."));
  } else if (s.kind === "tool") {
    li.append(el("code", "tstep-call", toolText(s.name, s.arguments)));
    li.append(s.ok === false ? el("p", "tstep-res bad", `Error: ${s.error || "failed"}`) : el("pre", "tstep-res", s.result || ""));
  } else {
    li.append(el("p", "tstep-note", s.text));
  }
  return li;
}

function inspectorHead(t) {
  const box = document.querySelector("#dialog-body .tinsp-head");
  if (!box) return;
  const facts = el("ul", "insp-facts");
  facts.append(el("li", null, `${STATUS[t.status] || t.status} · ${when(t)}`));
  if (t.model) facts.append(el("li", null, `${t.model}, thinking ${LEVELS[t.reasoning] || t.reasoning}${t.think && t.think !== t.reasoning ? ` (sent as ${t.think})` : ""}`));
  facts.append(el("li", null, `${budgetLine(t)} · ${span((t.spent || {}).seconds)} of ${span((t.budget || {}).seconds)}`));
  if (t.status === "running") facts.append(el("li", null, t.activity || "Working"));
  if (t.error) facts.append(el("li", "bad", t.error));
  box.replaceChildren(facts);
}

async function inspect(id) {
  const t = tasks.get(id);
  const body = openDialog(t ? t.title : "Task", { label: `Task · ${t ? whenLabel(t.created_at) : ""}`, wide: true });
  inspected = id;
  const goal = el("section", "insp-sec");
  goal.append(el("h3", null, "What you asked"), el("p", "insp-quote", t ? t.goal : ""));
  const head = el("div", "tinsp-head");
  goal.append(head);
  body.append(goal);
  const sec = el("section", "insp-sec");
  sec.append(el("h3", null, "Steps"));
  const list = el("ol", "tsteps");
  list.id = "task-steps";
  sec.append(list);
  body.append(sec);
  if (t) inspectorHead(t);
  try {
    const r = await full(id);
    if (inspected !== id) return;
    inspectorHead(r.task);
    list.replaceChildren(...r.steps.map(stepItem));
    if (!r.steps.length) list.append(el("li", "small muted", "No step yet."));
    if (r.task.summary) {
      const said = el("section", "insp-sec");
      said.append(el("h3", null, "What Marvin says"), el("p", "insp-quote", r.task.summary));
      body.append(said);
    }
  } catch (e) {
    list.replaceChildren(el("li", "bad", `The steps cannot be read: ${e.message}`));
  }
}

function addStep({ id, step }) {
  if (inspected !== id) return;
  const list = $("task-steps");
  if (!list || list.querySelector(`[data-n="${step.n}"]`)) return;
  if (list.firstElementChild && !list.firstElementChild.dataset.n) list.replaceChildren();
  list.append(stepItem(step));
}

// ------------------------------------------------------------------ Marvin > Voice & models: thinking and tasks

let reasoningSettings = null;

function fillLevels(name, value) {
  for (const r of document.querySelectorAll(`input[name="${name}"]`)) r.checked = r.value === value;
}

function renderReasoning(s) {
  reasoningSettings = s;
  $("reasoning-fields").disabled = false;
  $("reasoning-form").querySelector('button[type="submit"]').disabled = false;
  fillLevels("r-chat", s.settings.reasoning);
  fillLevels("r-task", s.settings.task_reasoning);
  $("r-tasks").checked = s.settings.tasks;
  const m = s.model || {}, th = m.thinking || {};
  const pill = $("reasoning-pill");
  pill.textContent = th.thinks === false ? "Cannot think" : th.thinks ? "Can think" : "Unknown";
  pill.className = `pill${th.thinks === false ? " attention-pill" : ""}`;
  const list = $("reasoning-model");
  const rows = [];
  const row = (k, v) => { const li = el("li"); li.append(el("span", null, k), el("span", "small muted", v)); rows.push(li); };
  row(m.name || "–", m.size_bytes ? `${(m.size_bytes / 1e9).toFixed(m.size_bytes < 1e10 ? 1 : 0)} GB` : "size unknown");
  if (th.thinks) row("Thinking levels", th.levels && th.levels.length ? th.levels.join(", ") : "on or off");
  if (th.thinks && s.settings.task_reasoning !== "off") row("Tasks are sent", String(m.think_tasks));
  list.replaceChildren(...rows);
  const hint = $("r-task-hint");
  hint.textContent = th.thinks === false ? `${m.name} cannot think: tasks run without reasoning. Choose a model that can (its Ollama capabilities include thinking).`
    : "Tasks think hard: better plans and fewer mistakes, slower answers. Marvin tells you when one is ready.";
  hint.classList.toggle("bad", th.thinks === false);
  const rec = s.recommended || {}, srv = s.server || {};
  const notes = [];
  if (!rec.current) notes.push(`For tasks, ${rec.model} (32 GB, 8-bit) reasons better than the 4-bit versions; ${rec.installed ? "it is installed" : `get it with ollama pull ${rec.model}`}, then choose it as the language model above. One model serves both: a second 27B would not fit beside the voice.`);
  notes.push(srv.yields_to_voice
    ? "Ollama answers one request at a time here, so a task pauses while you talk with Marvin and goes on after."
    : `Ollama answers ${srv.parallel} requests at once: tasks run beside the conversation.`);
  $("reasoning-notes").replaceChildren(...notes.map((n) => el("p", "hint", n)));
}

async function loadReasoning() {
  $("reasoning-error").hidden = true;
  try {
    renderReasoning(await api("/api/tasks/settings"));
  } catch (e) {
    $("reasoning-fields").disabled = true;
    $("reasoning-error").textContent = `Cannot be read: ${e.message}`;
    $("reasoning-error").hidden = false;
  }
}

function setupReasoning() {
  $("reasoning-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const err = $("reasoning-error");
    err.hidden = true;
    const f = ev.target;
    try {
      renderReasoning(await post("/api/tasks/settings", {
        reasoning: f.querySelector('input[name="r-chat"]:checked').value,
        task_reasoning: f.querySelector('input[name="r-task"]:checked').value,
        tasks: $("r-tasks").checked,
      }));
      const s = $("reasoning-saved");
      s.textContent = "Saved";
      setTimeout(() => { s.textContent = ""; }, 2500);
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
}

// ------------------------------------------------------------------ setup

export function setup() {
  $("task-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const input = $("task-goal"), err = $("task-error");
    err.hidden = true;
    const goal = input.value.trim();
    if (!goal) { input.focus(); return; }
    try {
      const r = await post("/api/tasks/start", { goal });
      input.value = "";
      upsert(r.task);
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
  $("tasks-more").addEventListener("click", () => { showAll = true; render(); });
  $("tasks-retry").addEventListener("click", () => load());
  on("task", upsert);
  on("task_step", addStep);
  on("dialog-closed", () => { inspected = null; });
  on("reconnected", () => load());
  on("view", ({ view, sub }) => {
    if (view === "activity" && sub === "") load();
    if (view === "marvin" && sub === "voice") loadReasoning();
  });
  setupReasoning();
}

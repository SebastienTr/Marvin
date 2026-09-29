// SPDX-License-Identifier: MIT
// Activity: what is in motion (background tasks: not yet; memory's worker: its passes and "Consolidate now"),
// today's moments, History (any day's timeline and numbers, the last seven days, the conversation of that day
// and a search over everything said), and the log.

import { $, app, el, api, post, on, emit, fmtDuration, fmtTime, timeEl, plural, isoDate, parseIso, dayLabel, longDate, whenLabel, reduceMotion, toast } from "./core.js";
import { DayCard } from "./daycard.js";
import { ConvoBuilder } from "./convo.js";

const QUIET_KINDS = new Set(["vitals_acquired", "vitals_lost"]);
const SYSTEM_KINDS = new Set(["host_started", "host_stopped", "robot_online", "robot_offline"]);
let historyDay;
let shown = null;             // the day History shows (YYYY-MM-DD); null = today
let history = [];
let lastEventId = 0;

// ------------------------------------------------------------------ memory's worker

function renderWorker(w) {
  const pill = $("worker-pill");
  pill.textContent = w.state === "running" ? "Working" : w.state === "off" ? "Off" : "Waiting";
  pill.className = `pill${w.state === "running" ? " attention-pill" : ""}`;
  const ul = el("ul", "worker-lines");
  const line = (a, b) => { const li = el("li"); li.append(el("span", null, a), el("span", null, b)); ul.append(li); };
  if (w.state === "running") line("Now", `${w.pass || "a"} pass${w.step ? `: ${w.step}` : ""}`);
  line("Waiting to be read", plural(w.pending || 0, "event", "events"));
  line("Last pass while you were away", w.last_idle_at ? whenLabel(w.last_idle_at) : "none yet");
  line("Last nightly pass", w.last_night_at ? whenLabel(w.last_night_at) : "none yet");
  if (w.next_night_at) line("Next nightly pass", w.next_night_at * 1000 < Date.now() ? "due, once you are away" : whenLabel(w.next_night_at));
  const models = w.models || {};
  line("Model", models.uses_voice_model ? "the voice’s model" : models.memory_model);
  if (models.night_model) line("At night", models.night_model);
  const nodes = [ul];
  if (w.last) {
    const l = w.last;
    const counts = Object.entries(l.counts || {}).map(([k, v]) => `${v} ${k.replace(/_/g, " ")}`).join(", ");
    nodes.push(el("p", "small muted gap-top", `Last: ${l.pass} pass ${l.outcome}${l.seconds != null ? ` in ${l.seconds.toFixed(1)} s` : ""}${counts ? ` (${counts})` : ""}.${l.error ? ` ${l.error}` : ""}${l.fix ? ` ${l.fix}` : ""}`));
  }
  const e = w.embeddings || {};
  if (e.state === "unavailable") nodes.push(el("p", "small gap-top notice-inline", `Search by meaning is unavailable. ${e.fix || e.error || ""}`));
  const acts = el("div", "worker-actions");
  const b = el("button", null, "Consolidate now");
  b.type = "button";
  b.disabled = w.state === "running" || w.state === "off";
  b.title = "Read what is waiting now, without waiting for you to be away. It pauses whenever Marvin talks.";
  b.addEventListener("click", async () => {
    b.disabled = true;
    try { renderWorker(await post("/api/memory/consolidate", {})); toast("Memory is reading what is waiting."); } catch (err) { toast(err.message); b.disabled = false; }
  });
  acts.append(b, el("span", "small muted", "It always gives way to the voice."));
  nodes.push(acts);
  $("worker-body").replaceChildren(...nodes);
}

async function loadWorker() {
  try { renderWorker(await api("/api/memory/worker")); } catch (e) { $("worker-body").replaceChildren(el("p", "small muted", "Memory is not available.")); }
}

// ------------------------------------------------------------------ today's moments

function eventItem(e) {
  const li = el("li");
  li.dataset.id = e.id;
  const p = el("span", null, e.text);
  if (SYSTEM_KINDS.has(e.kind)) p.className = "system";
  if (e.kind === "still_long") p.className = "attention";
  li.append(timeEl(e.ts), p);
  return li;
}

async function loadEvents() {
  const r = await api("/api/events?quiet=1&limit=50");
  const start = app.today ? app.today.start : 0;
  const today = r.events.filter((e) => e.ts >= start);
  $("all-events").replaceChildren(...today.map(eventItem));
  lastEventId = r.events.reduce((m, e) => Math.max(m, e.id), lastEventId);
  $("all-events-empty").hidden = today.length > 0;
  if (app.today) $("today-moments-date").textContent = longDate(app.today.date);
}

function addEvent(e) {
  if (e.id <= lastEventId) return;
  lastEventId = e.id;
  if (QUIET_KINDS.has(e.kind)) return;
  const li = eventItem(e);
  li.classList.add("fresh");
  $("all-events").prepend(li);
  $("all-events-empty").hidden = true;
}

// ------------------------------------------------------------------ history: days and weeks

async function showDay(iso) {
  shown = !app.today || iso === app.today.date ? null : iso;
  conversations.load(iso).catch(() => {});
  if (!shown) { if (app.today) historyDay.render(app.today); } else historyDay.render(await api(`/api/day?date=${iso}`));
  $("day-next").disabled = !shown;
  renderWeek();
}

function shiftDay(delta) {
  if (!app.today) return;
  const cur = parseIso(shown || app.today.date);
  cur.setDate(cur.getDate() + delta);
  const iso = isoDate(cur);
  if (iso > app.today.date) return;
  showDay(iso).catch(() => {});
}

async function loadWeek() {
  history = (await api("/api/history?days=7")).days;
  renderWeek();
}

function renderWeek() {
  const days = history.slice();
  if (!days.length) return;
  if (app.today) {
    const i = days.findIndex((d) => d.date === app.today.date);
    if (i >= 0) days[i] = { ...days[i], seated_s: app.today.seated_s };
  }
  const max = Math.max(3600 * 4, ...days.map((d) => d.seated_s));
  const cur = shown || (app.today && app.today.date);
  let sum = 0, n = 0;
  $("week").replaceChildren(...days.map((d) => {
    const li = el("li");
    const btn = el("button");
    btn.type = "button";
    const isToday = app.today && d.date === app.today.date;
    if (isToday) btn.classList.add("today");
    btn.setAttribute("aria-pressed", String(d.date === cur));
    const date = parseIso(d.date);
    btn.setAttribute("aria-label", `${date.toLocaleDateString("en-GB", { weekday: "long" })}: seated ${fmtDuration(d.seated_s)}`);
    btn.append(el("span", "val", d.seated_s >= 60 ? fmtDuration(d.seated_s, { short: true }) : ""));
    const col = el("span", "col"), fill = el("span", "fill");
    fill.style.height = `${Math.max(2, (d.seated_s / max) * 100)}%`;
    col.append(fill);
    btn.append(col, el("span", "day", date.toLocaleDateString("en-GB", { weekday: "short" })));
    btn.addEventListener("click", () => showDay(d.date).catch(() => {}));
    li.append(btn);
    if (!isToday && d.seated_s > 0) { sum += d.seated_s; n += 1; }
    return li;
  }));
  $("week-avg").textContent = n ? `${fmtDuration(sum / n)} a day on average` : "";
}

// ------------------------------------------------------------------ history: conversations

const conversations = {
  day: null,
  pending: null,
  seq: 0,
  timer: 0,

  setup() {
    const q = $("convo-q");
    $("convo-search").addEventListener("submit", (ev) => { ev.preventDefault(); this.search(q.value); });
    q.addEventListener("input", () => { clearTimeout(this.timer); this.timer = setTimeout(() => this.search(q.value), 250); });
    q.addEventListener("keydown", (ev) => { if (ev.key === "Escape" && q.value) { q.value = ""; this.search(""); } });
  },

  async load(iso) {
    const seq = ++this.seq;
    const r = await api(`/api/conversation?day=${iso}`);
    if (seq !== this.seq) return;
    this.day = r.day;
    $("convo-day").textContent = dayLabel(r.day);
    const b = new ConvoBuilder();
    const items = [];
    for (const e of r.entries) {
      const { li, grouped } = b.item(e);
      if (!grouped) { li.classList.add("settled"); items.push(li); }
    }
    $("convo").replaceChildren(...items);
    if (!$("convo-q").value.trim()) this.showResults(false);
    $("convo-empty").hidden = r.entries.length > 0 || !$("convo-results").hidden;
    $("convo-empty").textContent = app.today && r.day === app.today.date ? "No conversation yet today." : "No conversation that day.";
    if (this.pending != null) {
      const id = this.pending;
      this.pending = null;
      const li = $("convo").querySelector(`[data-id="${id}"]`);
      if (li) {
        li.scrollIntoView({ block: "center", behavior: reduceMotion.matches ? "auto" : "smooth" });
        li.classList.add("found");
        setTimeout(() => li.classList.remove("found"), 2400);
      }
    }
  },

  refreshToday() {
    if (shown || !app.today || this.refreshing) return;
    this.refreshing = setTimeout(() => { this.refreshing = 0; this.load(app.today.date).catch(() => {}); }, 2000);
  },

  showResults(onOff) {
    $("convo-results").hidden = !onOff;
    $("convo").hidden = onOff;
    if (!onOff) $("convo-count").textContent = "";
  },

  async search(text) {
    const q = text.trim();
    const seq = ++this.seq;
    if (q.length < 2) {
      this.showResults(false);
      $("convo-empty").hidden = $("convo").children.length > 0;
      return;
    }
    const r = await api(`/api/conversation?q=${encodeURIComponent(q)}`);
    if (seq !== this.seq) return;
    this.showResults(true);
    $("convo-empty").hidden = true;
    $("convo-count").textContent = r.results.length ? `${plural(r.results.length, "line", "lines")}${r.results.length >= 100 ? " (the latest 100)" : ""}` : `Nothing said matches “${q}”.`;
    $("convo-results").replaceChildren(...r.results.map((e) => this.result(e, q)));
  },

  result(e, q) {
    const li = el("li");
    const btn = el("button", "convo-hit");
    btn.type = "button";
    const d = new Date(e.t * 1000);
    const when = timeEl(e.t, `${d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" })} · ${fmtTime(e.t)}`);
    const text = el("span", "hit-text");
    const lower = e.text.toLowerCase(), k = lower.indexOf(q.toLowerCase());
    if (k >= 0) {
      const start = Math.max(0, k - 60);
      text.append((start ? "…" : "") + e.text.slice(start, k), el("mark", null, e.text.slice(k, k + q.length)), e.text.slice(k + q.length));
    } else text.textContent = e.text;
    btn.append(when, el("span", "hit-who", e.kind === "heard" ? "You" : "Marvin"), text);
    btn.addEventListener("click", () => this.jump(isoDate(d), e.id));
    li.append(btn);
    return li;
  },

  jump(iso, id) {
    this.pending = id;
    $("convo-q").value = "";
    this.showResults(false);
    showDay(iso).catch(() => {});
    $("convo-card").scrollIntoView({ block: "start", behavior: reduceMotion.matches ? "auto" : "smooth" });
  },
};

// ------------------------------------------------------------------ log

const SOURCE_LABEL = { brain: "Marvin", device: "Device", host: "Host", voice: "Voice" };
let log = [];

function logFilter() {
  const v = document.querySelector('input[name="log-filter"]:checked').value;
  return (e) => !v || (v === "warning" ? (e.level === "warning" || e.level === "error")
    : v === "brain" ? (e.source === "brain" || e.source === "host") : e.source === v);
}

function logItem(e) {
  const li = el("li");
  const dev = e.source === "device" && e.device;
  const text = el("span", `text ${e.level === "attention" ? "attention" : e.level === "warning" || e.level === "error" ? e.level : ""}`, e.text);
  li.append(timeEl(e.ts), el("span", dev ? "src dev" : "src", dev ? e.device : SOURCE_LABEL[e.source] || e.source), text);
  return li;
}

function renderLog() {
  const items = log.filter(logFilter()).slice(0, 200);
  $("log").replaceChildren(...items.map(logItem));
  $("log-empty").hidden = items.length > 0;
}

async function loadLog() {
  log = (await api("/api/log?limit=300")).entries;
  renderLog();
}

function addLog(e) {
  if (log.length && log[0].id >= e.id) return;
  log.unshift(e);
  if (log.length > 500) log.pop();
  if (app.view !== "activity" || app.sub !== "log" || !logFilter()(e)) return;
  const li = logItem(e);
  li.classList.add("fresh");
  $("log").prepend(li);
  while ($("log").children.length > 200) $("log").lastElementChild.remove();
  $("log-empty").hidden = true;
}

// ------------------------------------------------------------------ setup

export function setup() {
  historyDay = new DayCard($("day"));
  conversations.setup();
  $("day-prev").addEventListener("click", () => shiftDay(-1));
  $("day-next").addEventListener("click", () => shiftDay(1));
  for (const r of document.querySelectorAll('input[name="log-filter"]')) r.addEventListener("change", renderLog);
  on("today", (d) => {
    const newDay = history.length && !history.some((x) => x.date === d.date);
    if (!shown) historyDay.render(d);
    $("day-next").disabled = !shown;
    if (newDay) loadWeek().catch(() => {}); else renderWeek();
  });
  on("event", addEvent);
  on("log", addLog);
  on("transcript", ({ fresh }) => { if (fresh && app.view === "activity" && app.sub === "history") conversations.refreshToday(); });
  let workerTimer = 0;
  on("memory", (m) => {
    // the stream says the state changed; the details come from the API (at most once a second)
    if (m.kind === "worker" && !workerTimer) workerTimer = setTimeout(() => { workerTimer = 0; loadWorker(); }, 1000);
  });
  on("settings", () => historyDay.redraw());
  on("resize", () => historyDay.redraw());
  on("goto-conversation", ({ day, entry }) => {
    location.hash = "#activity/history";
    conversations.jump(day, entry);
  });
  on("view", ({ view, sub }) => {
    if (view !== "activity") return;
    if (sub === "history") {
      loadWeek().catch(() => {});
      if (shown) showDay(shown).catch(() => {});
      else if (app.today) { historyDay.render(app.today); conversations.load(app.today.date).catch(() => {}); }
    } else if (sub === "log") loadLog().catch(() => {});
    else { loadWorker(); loadEvents().catch(() => {}); }
  });
  on("reconnected", () => { loadWorker(); loadEvents().catch(() => {}); });
}

export async function load() {
  await Promise.all([loadWorker(), loadEvents(), loadLog()].map((p) => p.catch(() => {})));
  emit("activity-loaded");
}

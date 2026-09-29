// SPDX-License-Identifier: MIT
// Activity: what is in motion (tasks and approvals: an honest empty state until they exist; memory's worker),
// today's moments, and History: any day's timeline and numbers with the day in Marvin's words, the last seven
// days, the weeks in Marvin's words, the conversation of that day and a search over everything said.

import { $, app, el, api, on, emit, fmtDuration, fmtTime, timeEl, plural, isoDate, parseIso, dayLabel, longDate, reduceMotion } from "./core.js";
import { DayCard } from "./daycard.js";
import { ConvoBuilder } from "./convo.js";
import { episodeItem } from "./memory.js";
import * as worker from "./worker.js";

const QUIET_KINDS = new Set(["vitals_acquired", "vitals_lost"]);
const SYSTEM_KINDS = new Set(["host_started", "host_stopped", "robot_online", "robot_offline"]);
let historyDay;
let shown = null;             // the day History shows (YYYY-MM-DD); null = today
let history = [];
let lastEventId = 0;

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
  let r;
  try {
    r = await api("/api/events?quiet=1&limit=50");
  } catch (e) {
    $("all-events-loading").hidden = true;
    if (!$("all-events").children.length) {
      $("all-events-empty").hidden = false;
      $("all-events-empty").textContent = `Today’s moments cannot be read: ${e.message}`;
    }
    return;
  }
  $("all-events-loading").hidden = true;
  $("all-events-empty").textContent = "Nothing yet today.";
  const start = app.today ? app.today.start : 0;
  const today = r.events.filter((e) => e.ts >= start);
  $("all-events").replaceChildren(...today.map(eventItem));
  lastEventId = r.events.reduce((m, e) => Math.max(m, e.id), lastEventId);
  $("all-events-empty").hidden = today.length > 0;
  if (app.today) $("today-moments-date").textContent = longDate(app.today.date);
  moreButton();
}

function moreButton() {
  const list = $("all-events"), b = $("all-events-more");
  b.hidden = !list.classList.contains("capped") || list.children.length <= 12;
  b.textContent = `Show all ${list.children.length}`;
}

function addEvent(e) {
  if (e.id <= lastEventId) return;
  lastEventId = e.id;
  if (QUIET_KINDS.has(e.kind)) return;
  const li = eventItem(e);
  li.classList.add("fresh");
  $("all-events").prepend(li);
  $("all-events-empty").hidden = true;
  moreButton();
}

// ------------------------------------------------------------------ history: days and weeks

async function showDay(iso) {
  shown = !app.today || iso === app.today.date ? null : iso;
  conversations.load(iso).catch(() => {});
  daySummary(iso);
  if (!shown) { if (app.today) historyDay.render(app.today); } else historyDay.render(await api(`/api/day?date=${iso}`));
  $("day-next").disabled = !shown;
  renderWeek();
}

// the day and the weeks in Marvin's words (memory's episodes)
let summarySeq = 0;
async function daySummary(iso) {
  const n = ++summarySeq;
  const box = $("day-summary-text");
  const next = parseIso(iso);
  next.setDate(next.getDate() + 1);
  let text;
  try {
    const r = await api(`/api/memory/episodes?level=day&from=${iso}&to=${isoDate(next)}`);
    const e = r.episodes.find((x) => x.day === iso);
    if (e && e.stale) text = "Being written again tonight: something from that day was forgotten or made private.";
    else if (e) text = e.summary;
    else if (app.today && iso === app.today.date) text = "Marvin writes today tonight, from what happens until then.";
    else text = "Not written: nothing was kept from that day, or it was before memory.";
    box.classList.toggle("muted", !e);
  } catch (e) {
    text = `Cannot be read: ${e.message}`;
  }
  if (n === summarySeq) box.textContent = text;
}

async function loadWeeks() {
  const list = $("week-episodes");
  try {
    const r = await api("/api/memory/episodes?level=week");
    list.replaceChildren(...r.episodes.slice(0, 4).map(episodeItem));
    $("week-episodes-empty").hidden = r.episodes.length > 0;
    $("week-episodes-empty").textContent = "No week written yet. Marvin sums up a week the night after it ends.";
  } catch (e) {
    list.replaceChildren();
    $("week-episodes-empty").hidden = false;
    $("week-episodes-empty").textContent = `Cannot be read: ${e.message}`;
  }
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

// ------------------------------------------------------------------ setup

// ------------------------------------------------------------------ what waits for the owner (decided on Home)

function renderDecisions(pending) {
  const box = $("activity-decisions");
  emit("badge", { view: "activity", n: pending.length, label: `${pending.length} request${pending.length === 1 ? "" : "s"} waiting` });
  if (!pending.length) {
    box.replaceChildren(el("p", "small muted", "Today, the only thing that can wait for you is a request to forget. None is waiting."));
    return;
  }
  const ul = el("ul", "request-list");
  for (const p of pending) {
    const what = p.everything ? "Forget everything" : p.facts.length === 1 ? `Forget “${p.facts[0].statement}”`
      : `Forget ${p.facts.length} memories`;
    ul.append(el("li", null, `${what} · ${p.origin === "voice" ? "asked by voice" : "asked in the app"}, waiting until ${fmtTime(p.expires_at)}`));
  }
  const go = el("a", "button primary", pending.length === 1 ? "Decide on Home" : `Decide on Home (${pending.length})`);
  go.href = "#home";
  box.replaceChildren(el("p", "small", `${pending.length === 1 ? "A request waits" : `${pending.length} requests wait`} for your yes:`), ul, go);
}

export function setup() {
  historyDay = new DayCard($("day"));
  on("decisions", renderDecisions);
  conversations.setup();
  $("day-prev").addEventListener("click", () => shiftDay(-1));
  $("day-next").addEventListener("click", () => shiftDay(1));
  on("today", (d) => {
    const newDay = history.length && !history.some((x) => x.date === d.date);
    if (!shown) historyDay.render(d);
    $("day-next").disabled = !shown;
    if (newDay) loadWeek().catch(() => {}); else renderWeek();
  });
  on("event", addEvent);
  $("all-events-more").addEventListener("click", () => { $("all-events").classList.remove("capped"); moreButton(); });
  on("transcript", ({ fresh }) => { if (fresh && app.view === "activity" && app.sub === "history") conversations.refreshToday(); });
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
      loadWeeks();
      if (shown) showDay(shown).catch(() => {});
      else if (app.today) { historyDay.render(app.today); conversations.load(app.today.date).catch(() => {}); daySummary(app.today.date); }
    } else { worker.load(); loadEvents(); }
  });
  on("reconnected", () => { if (app.view === "activity") loadEvents(); });
}

export async function load() {
  await Promise.all([worker.load(), loadEvents()].map((p) => p.catch(() => {})));
}

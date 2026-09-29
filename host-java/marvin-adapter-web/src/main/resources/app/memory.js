// SPDX-License-Identifier: MIT
// Memory: the facts Marvin keeps (all, pinned, suggested, past; search; each with its source and date, "Source &
// edit", pin, keep), the profile it writes every night, the days and weeks in its words, its worker, and the
// owner's controls: the sources it may learn from, its settings, the raw log, export and "forget everything"; and
// "See your memory" (memviews.js): a graph, a meaning map, a timeline and the flow.

import { $, app, el, api, post, on, whenLabel, dayLabel, plural, parseIso, toast } from "./core.js";
import { sourceLine, statusPill, sensitivityPill, openFact, openRemember, setFlag } from "./memdata.js";
import { openProfile, openExport, openForgetEverything, openLog, openSettings, profileLines } from "./memtools.js";
import * as worker from "./worker.js";
import * as views from "./memviews.js";

const PAGE = 50;
let filter = "all";
let query = "";
let seq = 0;
let timer = 0;
let shown = [];
let offset = 0;
let total = 0;
let level = "day";
let allEpisodes = false;

const EMPTY = {
  all: ["Nothing remembered yet.", "Marvin learns from your conversations while you are away, and at once when you say “remember that…”. You can also add a memory yourself."],
  pinned: ["Nothing pinned.", "Pinned memories stay, whatever their age. Pin one from its row or its source."],
  suggested: ["Nothing to review.", "Facts Marvin learns on its own wait here until you keep, correct or forget them."],
  past: ["Nothing in the past.", "Facts that stopped being true, and those you archived, stay here with their dates. A corrected fact keeps its earlier versions in its own history."],
};
const HINT = {
  all: "",
  pinned: "Pinned memories never fade.",
  suggested: "Learned by Marvin on its own, not reviewed yet.",
  past: "No longer true, or archived: Marvin only finds these when you ask.",
};

// ------------------------------------------------------------------ facts

function actionButton(text, fn, cls = "") {
  const b = el("button", cls, text);
  b.type = "button";
  b.addEventListener("click", fn);
  return b;
}

function factItem(f) {
  const li = el("li", "fact");
  li.dataset.id = f.id;
  const tags = el("div", "fact-tags");
  tags.append(statusPill(f));
  const s = sensitivityPill(f);
  if (s) tags.append(s);
  const h = el("h3", null, f.statement);
  h.id = `fact-${f.id}`;
  li.append(tags, h, el("p", "source", sourceLine(f)));
  const acts = el("div", "fact-actions");
  const open = actionButton("Source & edit ↗", () => openFact(f.id));
  open.setAttribute("aria-describedby", h.id);
  acts.append(open);
  const current = f.status === "current" && !f.archived;
  if (current) {
    const pin = actionButton(f.pinned ? "Unpin" : "Pin", async () => { pin.disabled = true; if (!(await setFlag(f, "pinned", !f.pinned))) pin.disabled = false; });
    pin.setAttribute("aria-describedby", h.id);
    acts.append(pin);
  }
  if (current && f.origin !== "owner" && !f.reviewed) {
    const keep = actionButton("Keep", async () => { keep.disabled = true; if (!(await setFlag(f, "reviewed", true))) keep.disabled = false; });
    keep.setAttribute("aria-describedby", h.id);
    acts.append(keep);
  }
  if (f.archived && f.status === "current") {
    const back = actionButton("Bring back", async () => { back.disabled = true; if (!(await setFlag(f, "archived", false))) back.disabled = false; });
    back.setAttribute("aria-describedby", h.id);
    acts.append(back);
  }
  li.append(acts);
  return li;
}

async function fetchFacts(from) {
  const q = query ? `&q=${encodeURIComponent(query)}` : "";
  if (filter !== "past") return api(`/api/memory/facts?filter=${filter}&limit=${PAGE}&offset=${from}${q}`);
  // "Past" is the past and the archived, together, newest first
  const [p, a] = await Promise.all([
    api(`/api/memory/facts?filter=past&limit=${PAGE}&offset=${from}${q}`),
    api(`/api/memory/facts?filter=archived&limit=${PAGE}&offset=${from}${q}`),
  ]);
  const facts = [...p.facts, ...a.facts].sort((x, y) => (y.expired_at || y.learned_at) - (x.expired_at || x.learned_at));
  return { facts, total: p.total + a.total, counts: p.counts, more: p.facts.length === PAGE || a.facts.length === PAGE };
}

function setCounts(counts) {
  if (!counts) return;
  const n = { ...counts, past: (counts.past || 0) + (counts.archived || 0) };
  for (const c of document.querySelectorAll("[data-memory-count]")) {
    const v = n[c.dataset.memoryCount];
    c.textContent = v ? ` ${v}` : "";
  }
  for (const b of document.querySelectorAll("[data-memory-filter]")) {
    const v = n[b.dataset.memoryFilter] || 0;
    b.setAttribute("aria-label", `${b.firstChild.textContent}, ${v}`);
  }
}

function showEmpty(title, text, retry) {
  $("memory-empty").hidden = false;
  $("memory-empty-title").textContent = title;
  $("memory-empty-text").textContent = text;
  $("memory-empty-retry").hidden = !retry;
}

async function loadFacts({ more = false } = {}) {
  const n = ++seq;
  if (!more) { offset = 0; $("memory-loading").hidden = shown.length > 0; }
  $("fact-list").setAttribute("aria-busy", "true");
  let r;
  try {
    r = await fetchFacts(more ? offset : 0);
  } catch (e) {
    if (n !== seq) return;
    $("memory-loading").hidden = true;
    $("fact-list").removeAttribute("aria-busy");
    if (!more) {
      shown = [];
      $("fact-list").replaceChildren();
      $("memory-more").hidden = true;
      $("memory-bulk").hidden = true;
      $("memory-count").textContent = "";
      showEmpty("Memory cannot be read.", app.connection === "lost" ? "Marvin’s host is not answering. It will come back on its own." : e.message, true);
    } else toast(`Cannot load more: ${e.message}`);
    return;
  }
  if (n !== seq) return;
  $("memory-loading").hidden = true;
  $("fact-list").removeAttribute("aria-busy");
  shown = more ? shown.concat(r.facts) : r.facts;
  offset = (more ? offset : 0) + PAGE;
  total = r.total;
  if (more) $("fact-list").append(...r.facts.map(factItem));
  else $("fact-list").replaceChildren(...r.facts.map(factItem));
  setCounts(r.counts);
  $("memory-count").textContent = query ? `${plural(total, "match", "matches")}` : plural(total, "fact", "facts");
  $("memory-filter-hint").textContent = HINT[filter];
  $("memory-filter-hint").hidden = !HINT[filter];
  $("memory-more").hidden = !(r.more != null ? r.more : shown.length < total);
  $("memory-empty-retry").hidden = true;
  if (shown.length) $("memory-empty").hidden = true;
  else if (query) showEmpty("Nothing matches.", `No memory in “${filterName()}” matches “${query}”.`);
  else showEmpty(...EMPTY[filter]);
  const suggested = filter === "suggested" ? shown.filter((f) => !f.reviewed) : [];
  $("memory-bulk").hidden = suggested.length < 2;
  $("memory-bulk-text").textContent = `${plural(suggested.length, "suggestion", "suggestions")} shown. Keep the ones you agree with, or all of them at once.`;
}

function filterName() {
  return document.querySelector(`[data-memory-filter="${filter}"]`).firstChild.textContent;
}

async function reviewAll() {
  const ids = shown.filter((f) => !f.reviewed).map((f) => f.id);
  if (!ids.length) return;
  const b = $("memory-review-all");
  b.disabled = true;
  try {
    await post("/api/memory/facts/review", { ids, reviewed: true });
    toast(`${plural(ids.length, "memory", "memories")} kept.`);
    loadFacts();
  } catch (e) {
    toast(`Not saved: ${e.message}`);
  }
  b.disabled = false;
}

// ------------------------------------------------------------------ profile

async function loadProfile() {
  const box = $("profile-body");
  let r;
  try { r = await api("/api/memory/profile?limit=1"); } catch (e) {
    box.replaceChildren(el("p", "small muted gap-top", `Your profile cannot be read: ${e.message}`));
    return;
  }
  const p = r.current;
  $("profile-tokens").textContent = p && p.tokens ? `${p.tokens} tokens` : "";
  if (!p || !p.content.trim()) {
    box.replaceChildren(el("p", "profile-name", "Not written yet."),
      el("p", "small muted", "Each night Marvin writes a short profile from what it learned, and uses it in every conversation. You can write it yourself, too; your own lines are always kept."));
    return;
  }
  const lines = profileLines(p);
  const ul = el("ul", "profile-lines");
  for (const { text, kept } of lines.slice(0, 5)) ul.append(el("li", kept ? "kept" : "", text));
  const nodes = [el("p", "profile-name", "A few things about you."),
    el("p", "small muted", `Version ${p.id} · ${p.author === "owner" ? "written by you" : "written by Marvin"} · ${whenLabel(p.created_at)}`), ul];
  if (lines.length > 5) nodes.push(el("p", "small muted", `and ${plural(lines.length - 5, "more line", "more lines")}`));
  box.replaceChildren(...nodes);
}

// ------------------------------------------------------------------ episodes

function episodeTitle(e) {
  if (e.level === "day") return dayLabel(e.day);
  const d = parseIso(e.day);
  if (e.level === "week") return `Week of ${d.toLocaleDateString("en-GB", { day: "numeric", month: "long" })}`;
  return d.toLocaleDateString("en-GB", { month: "long", year: "numeric" });
}

export function episodeItem(e) {
  const li = el("li", "episode");
  const head = el("div", "episode-head");
  head.append(el("span", "episode-title", episodeTitle(e)));
  head.append(el("span", "small muted", e.stale ? "rewritten tonight" : plural(e.events, "moment", "moments")));
  // a stale summary is blanked at once (it may say what was forgotten or made private) until it is written again
  li.append(head, e.summary ? el("p", null, e.summary)
    : el("p", "muted", "Being written again: something in it was forgotten or made private."));
  return li;
}

async function loadEpisodes() {
  const list = $("episodes"), empty = $("episodes-empty");
  let r;
  try { r = await api(`/api/memory/episodes?level=${level}`); } catch (e) {
    list.replaceChildren();
    empty.hidden = false;
    empty.textContent = `Cannot be read: ${e.message}`;
    return;
  }
  const eps = r.episodes;
  const max = allEpisodes ? eps.length : 4;
  list.replaceChildren(...eps.slice(0, max).map(episodeItem));
  $("episodes-more").hidden = eps.length <= max;
  $("episodes-more").textContent = `Show all ${eps.length}`;
  empty.hidden = eps.length > 0;
  empty.textContent = {
    day: "No day written yet. Marvin writes each day the night after, from what happened.",
    week: "No week written yet. Marvin sums up a week from its days, the night after it ends.",
    month: "No month written yet. Marvin sums up a month from its weeks.",
  }[level];
  const today = $("episodes-today");
  today.hidden = level !== "day";
  const w = worker.current();
  today.textContent = `Today is written tonight${w && w.next_night_at && w.next_night_at * 1000 > Date.now() ? `, after ${whenLabel(w.next_night_at).split(", ").pop()}` : ""}, from what happens until then.`;
}

// ------------------------------------------------------------------ sources and notices

async function loadSources() {
  const boxes = document.querySelectorAll("[data-source]");
  try {
    const r = await api("/api/memory/settings");
    for (const b of boxes) { b.checked = r.settings[b.dataset.source] !== false; b.disabled = false; }
  } catch (e) {
    for (const b of boxes) b.disabled = true;
    $("memory-sources-note").textContent = `The settings cannot be read: ${e.message}`;
  }
}

async function setSource(box) {
  const on = box.checked;
  box.disabled = true;
  try {
    await post("/api/memory/settings", { [box.dataset.source]: on });
    toast(on ? "Marvin learns from it again." : "Switched off: from now on, nothing from it reaches memory.");
  } catch (e) {
    box.checked = !on;
    toast(`Not saved: ${e.message}`);
  }
  box.disabled = false;
}

function renderNotice(w) {
  const n = $("memory-notice");
  const e = w && w.embeddings;
  if (!e || e.state !== "unavailable") { n.hidden = true; return; }
  n.hidden = false;
  $("memory-notice-title").textContent = `Search by meaning is off: ${e.model} is not available.`;
  $("memory-notice-text").textContent = "Marvin still remembers, and finds memories by their words; it will find them by meaning once the model is there. "
    + (e.error ? `(${e.error})` : "");
  $("memory-notice-fix").hidden = !e.fix;
  $("memory-notice-code").textContent = e.fix || "";
}

// ------------------------------------------------------------------ setup

export function setup() {
  worker.setup();
  views.setup();
  for (const b of document.querySelectorAll("[data-memory-filter]")) {
    b.addEventListener("click", () => {
      filter = b.dataset.memoryFilter;
      for (const x of document.querySelectorAll("[data-memory-filter]")) x.setAttribute("aria-pressed", String(x === b));
      shown = [];
      loadFacts();
    });
  }
  $("memory-search").addEventListener("input", (ev) => {
    clearTimeout(timer);
    timer = setTimeout(() => { query = ev.target.value.trim(); loadFacts(); }, 250);
  });
  $("memory-search").addEventListener("keydown", (ev) => {
    if (ev.key === "Escape" && ev.target.value) { ev.target.value = ""; query = ""; loadFacts(); }
  });
  $("memory-more").addEventListener("click", () => loadFacts({ more: true }));
  $("memory-empty-retry").addEventListener("click", () => load());
  $("memory-review-all").addEventListener("click", reviewAll);
  $("memory-add").addEventListener("click", openRemember);
  $("memory-export").addEventListener("click", openExport);
  $("profile-open").addEventListener("click", openProfile);
  $("memory-settings").addEventListener("click", openSettings);
  $("memory-log").addEventListener("click", openLog);
  $("memory-forget-all").addEventListener("click", openForgetEverything);
  $("memory-notice-retry").addEventListener("click", async () => { renderNotice(await worker.load()); toast("Checked again."); });
  $("episodes-more").addEventListener("click", () => { allEpisodes = true; loadEpisodes(); });
  for (const r of document.querySelectorAll('input[name="episode-level"]')) {
    r.addEventListener("change", () => { if (r.checked) { level = r.value; allEpisodes = false; loadEpisodes(); } });
  }
  for (const b of document.querySelectorAll("[data-source]")) b.addEventListener("change", () => setSource(b));
  on("worker", renderNotice);
  on("view", ({ view }) => { if (view === "memory") load(); });
  const visible = () => document.body.dataset.view === "memory";
  on("fact-changed", () => { if (visible()) { loadFacts(); loadProfile(); } });
  on("memory", (m) => {
    if (m.kind !== "changed" || !visible()) return;
    if (m.what === "profile") loadProfile();
    else if (m.what === "facts") loadFacts();
    else if (m.what === "log") loadEpisodes();
    else load();
  });
  on("reconnected", () => { if (visible()) load(); });
}

export async function load() {
  await Promise.all([loadFacts(), loadProfile(), worker.load().then(() => loadEpisodes()), loadSources(), views.load()]);
}

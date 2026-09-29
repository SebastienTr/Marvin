// SPDX-License-Identifier: MIT
// Memory: the facts Marvin keeps, each with its source (all, pinned, suggested; search), the profile it writes
// every night, and the export. Every fact opens with its sources, quoted and dated.

import { $, el, api, on, whenLabel, plural } from "./core.js";
import { sourceLine, statusPill, openFact } from "./memdata.js";

let filter = "all";
let query = "";
let seq = 0;
let timer = 0;

const EMPTY = {
  all: "Marvin learns from your conversations while you are away, and at once when you say “remember that…”.",
  pinned: "Pinned memories stay, whatever their age. Nothing is pinned yet.",
  suggested: "Facts Marvin learned on its own wait here for your review. None right now.",
};

function factItem(f) {
  const li = el("li", "fact");
  li.dataset.id = f.id;
  const tags = el("div", "fact-tags");
  tags.append(statusPill(f));
  if (f.sensitivity === "sensitive") tags.append(el("span", "pill", "Sensitive"));
  if (f.sensitivity === "personal") tags.append(el("span", "pill", "Personal"));
  li.append(tags, el("h3", null, f.statement), el("p", "source", sourceLine(f)));
  const acts = el("div", "fact-actions");
  const b = el("button", null, "Source & details");
  b.type = "button";
  b.addEventListener("click", () => openFact(f.id));
  acts.append(b);
  li.append(acts);
  return li;
}

async function loadFacts() {
  const n = ++seq;
  let r;
  try {
    r = await api(`/api/memory/facts?filter=${filter}&limit=50${query ? `&q=${encodeURIComponent(query)}` : ""}`);
  } catch (e) {
    if (n !== seq) return;
    $("fact-list").replaceChildren();
    $("memory-empty").hidden = false;
    $("memory-empty-text").textContent = `Memory is not available: ${e.message}`;
    return;
  }
  if (n !== seq) return;
  $("fact-list").replaceChildren(...r.facts.map(factItem));
  $("memory-count").textContent = plural(r.total, "fact", "facts");
  $("memory-empty").hidden = r.facts.length > 0;
  $("memory-empty-text").textContent = query ? `Nothing matches “${query}”.` : EMPTY[filter];
  for (const b of document.querySelectorAll("[data-memory-filter]")) {
    const c = r.counts[b.dataset.memoryFilter];
    b.setAttribute("aria-label", `${b.textContent.replace(/ \(\d+\)$/, "")}, ${c}`);
  }
}

async function loadProfile() {
  const box = $("profile-body");
  let r;
  try { r = await api("/api/memory/profile?limit=1"); } catch (e) { box.replaceChildren(el("p", "small muted gap-top", "Not available.")); return; }
  const p = r.current;
  if (!p || !p.content.trim()) {
    box.replaceChildren(el("p", "profile-name", "Not written yet."),
      el("p", "small muted", "Each night Marvin writes a short profile from what it learned, and uses it in every conversation. Your own lines are always kept."));
    return;
  }
  const kept = new Set(p.kept_lines || []);
  const ul = el("ul", "profile-lines gap-top");
  for (const line of p.content.split("\n").map((l) => l.replace(/^[-*]\s*/, "").trim()).filter(Boolean)) {
    ul.append(el("li", kept.has(line) ? "kept" : "", line));
  }
  box.replaceChildren(el("p", "small muted gap-top", `Version ${p.id} · ${p.author === "owner" ? "written by you" : "written by Marvin"} · ${whenLabel(p.created_at)}`), ul);
}

async function loadWorker() {
  try {
    const w = await api("/api/memory/worker");
    const last = w.last_night_at ? `Last nightly pass ${whenLabel(w.last_night_at)}.` : "No nightly pass yet.";
    const emb = w.embeddings && w.embeddings.state === "unavailable" ? ` Search by meaning is unavailable: ${w.embeddings.fix || w.embeddings.error}` : "";
    $("memory-worker-line").textContent = `${last}${emb}`;
  } catch (e) { /* the line stays empty */ }
}

export function setup() {
  for (const b of document.querySelectorAll("[data-memory-filter]")) {
    b.addEventListener("click", () => {
      filter = b.dataset.memoryFilter;
      for (const x of document.querySelectorAll("[data-memory-filter]")) x.setAttribute("aria-pressed", String(x === b));
      loadFacts();
    });
  }
  $("memory-search").addEventListener("input", (ev) => {
    clearTimeout(timer);
    timer = setTimeout(() => { query = ev.target.value.trim(); loadFacts(); }, 250);
  });
  on("view", ({ view }) => { if (view === "memory") load(); });
  on("memory", (m) => {
    if (m.kind === "changed" && document.body.dataset.view === "memory") {
      if (m.what === "profile") loadProfile(); else loadFacts();
    }
  });
}

export async function load() {
  await Promise.all([loadFacts(), loadProfile(), loadWorker()]);
}

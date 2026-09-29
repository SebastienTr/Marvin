// SPDX-License-Identifier: MIT
// Memory's worker as the app shows it, on Memory (in full) and on Activity (compact): what it is doing, its last
// passes and what they did, the next night, its models, the embedding model's state with its fix, and
// "Consolidate now". One request feeds every panel; the stream says when it changed.

import { el, api, post, on, emit, whenLabel, plural, toast } from "./core.js";

let last = null;
let failed = "";
let timer = 0;

const STEP_WORDS = {
  extract: "reading what happened", embeddings: "indexing by meaning", days: "writing the days",
  "roll-ups": "writing the weeks", profile: "rewriting your profile", decay: "letting old things fade",
  retention: "tidying the old log",
};

const COUNT_WORDS = [
  ["added", "new fact", "new facts"], ["updated", "fact updated", "facts updated"],
  ["invalidated", "no longer true", "no longer true"], ["days", "day written", "days written"],
  ["roll_ups", "week or month written", "weeks or months written"], ["profile", "profile rewritten", "profile rewrites"],
  ["archived", "faded to the archive", "faded to the archive"],
];

/** "3 new facts, 5 days written" from a pass's counts; "nothing new" when nothing changed. */
export function passSummary(counts) {
  const parts = [];
  for (const [k, one, many] of COUNT_WORDS) {
    const n = counts && counts[k];
    if (n) parts.push(`${n} ${n === 1 ? one : many}`);
  }
  return parts.length ? parts.join(", ") : "nothing new";
}

function stateWords(w) {
  if (w.state === "running") return ["Working", true];
  if (w.state === "off") return ["Off", false];
  return ["Waiting", false];
}

/** The embedding model's problem, or null: shared with Memory's notice. */
export function embeddingProblem(w) {
  const e = (w && w.embeddings) || {};
  if (e.state !== "unavailable") return null;
  return { model: e.model, error: e.error, fix: e.fix };
}

function render(w) {
  for (const pill of document.querySelectorAll("[data-worker-pill]")) {
    if (!w) { pill.textContent = failed ? "Unavailable" : "–"; pill.className = "pill"; continue; }
    const [words, busy] = stateWords(w);
    pill.textContent = words;
    pill.className = `pill${busy ? " attention-pill" : ""}`;
  }
  for (const box of document.querySelectorAll("[data-worker-body]")) {
    if (!w) {
      const p = el("p", "small muted", failed ? `Memory’s worker cannot be read: ${failed}` : "Loading…");
      const again = el("button", "small-button gap-top", "Try again");
      again.type = "button";
      again.addEventListener("click", () => load());
      box.replaceChildren(p, ...(failed ? [again] : []));
      continue;
    }
    box.replaceChildren(...body(w, box.dataset.workerBody === "full"));
  }
}

function body(w, full) {
  const ul = el("ul", "worker-lines");
  const line = (a, b) => { const li = el("li"); li.append(el("span", null, a), el("span", null, b)); ul.append(li); };
  if (w.state === "running") line("Now", `${w.pass === "nightly" ? "the nightly pass" : "a pass while you are away"}${w.step ? `, ${STEP_WORDS[w.step] || w.step}` : ""}`);
  if (w.state === "off") line("Now", "switched off in memory’s settings");
  line("Waiting to be read", plural(w.pending || 0, "moment", "moments"));
  line("Last pass while you were away", w.last_idle_at ? whenLabel(w.last_idle_at) : "none yet");
  line("Last nightly pass", w.last_night_at ? whenLabel(w.last_night_at) : "none yet");
  if (w.next_night_at && w.state !== "off") line("Next nightly pass", w.next_night_at * 1000 < Date.now() ? "due, once you are away" : whenLabel(w.next_night_at));
  if (full) {
    const models = w.models || {};
    line("Model", models.uses_voice_model ? "the voice’s model" : models.memory_model);
    line("At night", models.night_model || (models.uses_voice_model ? "the voice’s model" : models.memory_model));
    line("Remembered", `${plural(w.facts || 0, "fact", "facts")} from ${plural(w.events || 0, "moment", "moments")}`);
  }
  const nodes = [ul];
  if (w.last) {
    const l = w.last;
    const what = l.pass === "nightly" ? "Last nightly pass" : "Last pass";
    const ok = l.outcome === "done";
    const p = el("p", `small gap-top${ok ? " muted" : " attention-text"}`);
    p.textContent = ok
      ? `${what}: ${passSummary(l.counts)}${l.seconds != null ? `, in ${l.seconds < 10 ? l.seconds.toFixed(1) : Math.round(l.seconds)} s` : ""}${l.model ? ` with ${l.model}` : ""}.`
      : `${what} ${l.outcome === "yielded" ? "gave way to the voice; it resumes when Marvin is quiet" : l.outcome === "skipped" ? "was skipped: another pass was running" : `did not finish: ${l.error || l.outcome}`}.${l.fix ? ` ${l.fix}` : ""}`;
    nodes.push(p);
  }
  const problem = embeddingProblem(w);
  if (problem) {
    const n = el("div", "notice inline-notice");
    n.append(el("p", "notice-title", `Search by meaning is off: ${problem.model} is not available.`));
    if (problem.fix) { const f = el("p", "notice-fix"); f.append(el("span", "muted", "Fix: "), el("code", null, problem.fix)); n.append(f); }
    nodes.push(n);
  }
  const acts = el("div", "worker-actions");
  const now = el("button", null, "Consolidate now");
  now.type = "button";
  now.disabled = w.state === "running" || w.state === "off";
  now.title = "Read what is waiting now, without waiting for you to be away. It pauses whenever Marvin talks.";
  now.addEventListener("click", () => consolidate("idle", now));
  acts.append(now);
  if (full) {
    const night = el("button", "quiet", "Run the nightly pass");
    night.type = "button";
    night.disabled = now.disabled;
    night.title = "Also write the days and weeks, rewrite your profile and let old things fade, now.";
    night.addEventListener("click", () => consolidate("nightly", night));
    acts.append(night);
  }
  acts.append(el("span", "small muted", "It always gives way to the voice."));
  nodes.push(acts);
  return nodes;
}

async function consolidate(pass, button) {
  button.disabled = true;
  try {
    last = await post("/api/memory/consolidate", { pass });
    render(last);
    toast(pass === "nightly" ? "The nightly pass has started." : "Memory is reading what is waiting.");
  } catch (e) {
    toast(`Cannot start: ${e.message}`);
    button.disabled = false;
  }
}

/** Reads the worker and redraws every panel that shows it. */
export async function load() {
  try {
    last = await api("/api/memory/worker");
    failed = "";
    emit("worker", last);
  } catch (e) {
    failed = e.message;
    last = null;
  }
  render(last);
  return last;
}

export const current = () => last;

export function setup() {
  on("memory", (m) => {
    // the stream says the state changed; the details come from the API (at most once a second)
    if ((m.kind === "worker" || (m.kind === "changed" && m.what === "settings")) && !timer) {
      timer = setTimeout(() => { timer = 0; load(); }, 1000);
    }
  });
  on("reconnected", () => load());
  on("theme", () => render(last));
}

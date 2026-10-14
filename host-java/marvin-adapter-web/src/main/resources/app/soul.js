// SPDX-License-Identifier: MIT
// Marvin's soul (docs/design.md 8): the core you write (its character, what it will not do, how it speaks to you), and
// what it learned about how to be with you, proposed once a week by its reflection and changed only when you approve.
// A proposal shows as a diff with the guardrails' verdicts; one that fails a guardrail shows as rejected, with why.
// Every version is kept: go back to any.
//
// setup(root) renders into `root` (W5 places it, under Marvin); load() reads /api/soul.

import { el, api, on, whenLabel, toast } from "./core.js";

const RULES = { no_manipulation: "No manipulation", no_dependency: "No dependency", honesty: "Honesty", bounded: "Within bounds" };
const OPS = { keep: ["·", ""], add: ["+", "add"], remove: ["−", "del"] };

let root = null;
let soul = null;
let failed = "";
let refusal = null;          // the guardrails' verdicts of the last refused edit

function button(text, cls, fn) {
  const b = el("button", cls, text);
  b.type = "button";
  b.addEventListener("click", fn);
  return b;
}

/** About as many tokens as the host counts (English, 4 characters a token, 10 % margin). */
function tokens(text) {
  const n = [...(text || "").trim()].length;
  return n ? Math.ceil((n / 4) * 1.1) : 0;
}

function guardrails(list) {
  const ul = el("ul", "guardrails small");
  for (const g of list || []) {
    const li = el("li", g.ok ? "ok" : "failed");
    li.append(el("span", null, `${g.ok ? "✓" : "✗"} ${RULES[g.rule] || g.rule}`));
    if (!g.ok && g.reason) li.append(el("span", "muted", ` — ${g.reason}`));
    ul.append(li);
  }
  return ul;
}

function proposal(p) {
  const box = el("section", "panel soul-proposal");
  const head = el("div", "panel-head");
  const rejected = p.state === "rejected";
  head.append(el("span", "label muted", "This week’s reflection"), el("span", `pill${rejected ? " attention-pill" : ""}`,
    rejected ? "Refused by the guardrails" : "Waiting for you"));
  box.append(head);
  if (p.rationale) box.append(el("p", null, p.rationale));
  const diff = el("ul", "diff");
  for (const d of p.diff || []) {
    const [sym, cls] = OPS[d.op] || ["?", ""];
    const li = el("li", cls);
    li.append(el("span", "op", sym), d.text);
    diff.append(li);
  }
  box.append(diff);
  if (p.events && p.events.length) box.append(el("p", "small muted", `From ${p.events.length} thing${p.events.length > 1 ? "s" : ""} you said this week.`));
  box.append(guardrails(p.guardrails));
  const actions = el("div", "row-actions");
  if (!rejected) {
    actions.append(button("Approve", "primary small-button", () => act(`/api/soul/proposal/${p.id}/approve`, {}, "Marvin learned it")));
    actions.append(button("Reject", "small-button", () => act(`/api/soul/proposal/${p.id}/reject`, {}, "Nothing changed")));
  } else {
    actions.append(button("Dismiss", "small-button", () => act(`/api/soul/proposal/${p.id}/reject`, {}, "Dismissed")));
  }
  box.append(actions);
  return box;
}

function editor(part, title, hint, limit) {
  const cur = soul[part];
  const box = el("section", "panel form");
  const head = el("div", "panel-head");
  const count = el("span", "pill", `${cur.tokens} / ${limit} tokens`);
  head.append(el("span", "label muted", title), count);
  box.append(head);
  box.append(el("p", "small muted", hint));
  const field = el("div", "field");
  const ta = el("textarea");
  ta.rows = 5;
  ta.value = cur.text;
  ta.setAttribute("aria-label", title);
  ta.addEventListener("input", () => {
    const n = tokens(ta.value);
    count.textContent = `${n} / ${limit} tokens`;
    count.classList.toggle("attention-pill", n > limit);
  });
  field.append(ta);
  box.append(field);
  if (part === "learned" && refusal) box.append(guardrails(refusal));
  const actions = el("div", "row-actions");
  actions.append(button("Save", "primary small-button", () => act(`/api/soul/${part}`, { text: ta.value }, "Saved")));
  actions.append(el("span", "small muted", cur.version ? `Version ${cur.version}${cur.updated ? `, ${whenLabel(cur.updated)}` : ""}` : "Nothing yet"));
  box.append(actions);
  return box;
}

function versions() {
  const box = el("section", "panel");
  const head = el("div", "panel-head");
  head.append(el("span", "label muted", "Versions"));
  box.append(head);
  const ul = el("ul", "soul-versions stack");
  for (const v of soul.versions || []) {
    const li = el("li", "row");
    const who = v.author === "reflection" ? "the reflection, approved by you" : "you";
    const text = el("span", "small");
    text.append(el("strong", null, `${v.part === "core" ? "Core" : "Learned"} v${v.version}`),
      ` · ${whenLabel(v.created)} · ${who}${v.note ? ` · ${v.note}` : ""}`);
    li.append(text);
    if (v.version !== soul[v.part].version) {
      li.append(button("Go back to it", "small-button", () => act("/api/soul/revert", { part: v.part, version: v.version },
        `Back to ${v.part} v${v.version}`)));
    }
    ul.append(li);
  }
  if (!ul.children.length) ul.append(el("li", "small muted", "No version yet."));
  box.append(ul);
  return box;
}

async function act(path, body, done) {
  const r = await fetch(path, {
    method: "POST", credentials: "same-origin", cache: "no-store",
    headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).catch(() => null);
  const json = r ? await r.json().catch(() => ({})) : {};
  if (r && r.ok) {
    soul = json;
    refusal = null;
    toast(done);
    render();
    return;
  }
  toast(json.error || (r ? `HTTP ${r.status}` : "Marvin’s host did not answer"));
  refusal = r && r.status === 422 ? json.guardrails || null : null;
  await load(true);
}

function render() {
  if (!root) return;
  root.replaceChildren();
  if (failed) {
    root.append(el("p", "small muted", failed), button("Try again", "small-button gap-top", () => load()));
    return;
  }
  if (!soul) {
    root.append(el("p", "small muted", "Reading Marvin’s soul…"));
    return;
  }
  if (soul.proposal) root.append(proposal(soul.proposal));
  root.append(editor("core", "Core", "Written by you only: Marvin’s character, what it will not do, how it speaks to you. "
    + "In English (the soul is kept in English; Marvin still answers in your language).", soul.limits.core_tokens));
  root.append(editor("learned", "Learned", "What Marvin learned about how to be with you. Its weekly reflection proposes "
    + "changes; nothing changes until you approve. Your own edits are checked by the same guardrails.", soul.limits.learned_tokens));
  const row = el("div", "row-actions");
  row.append(button("Reflect now", "small-button", () => act("/api/soul/reflect", {}, "Reflection done")),
    el("span", "small muted", "It runs by itself on Sunday night, or at the first quiet moment after."));
  root.append(row, versions());
}

/** Reads the soul; `keepRefusal` keeps the last refused edit's verdicts on screen. */
export async function load(keepRefusal) {
  try {
    soul = await api("/api/soul");
    failed = "";
  } catch (e) {
    failed = `Could not read Marvin’s soul: ${e.message}`;
  }
  if (!keepRefusal) refusal = null;
  render();
}

/** Renders into `target` (an element, or its id). */
export function setup(target) {
  root = typeof target === "string" ? document.getElementById(target) : target || null;
  on("reconnected", () => load());
  render();
}

// SPDX-License-Identifier: MIT
// One line of the conversation per entry ("heard", "reply", "ignored", "note"), the same for the live chat
// (Talk) and past days (Activity > History). Answers open their inspector; a remember, recall or forget tool
// leaves a chip under the answer; consecutive ignored entries fold into one discreet line that opens to show
// each one and why. A question said in several breaths (the owner went on after a pause) is one bubble: its entry
// `replaces` the bubbles of the earlier parts, or `continues` them when Marvin had started answering in between.
// A question asked with an image shows it above its words (the thumbnail this tab kept, else its size).

import { el, fmtTime, fmtSeconds, plural, reduceMotion } from "./core.js";
import { openInspector } from "./inspector.js";
import { imageFigure } from "./attach.js";

let seq = 0;

function who(text, e) {
  const w = el("span", "who", text);
  w.append(" · ");
  const t = el("time", null, fmtTime(e.t));
  t.dateTime = new Date(e.t * 1000).toISOString();
  w.append(t);
  return w;
}

/** Chips for the memory tools an answer used: what was remembered, looked up, or is waiting to be forgotten. */
function memoryChips(e) {
  const calls = (e.tools || []).filter((c) => ["remember", "recall", "forget"].includes(c.name));
  if (!calls.length) return null;
  const row = el("div", "chips-row");
  for (const c of calls) {
    const a = el("a", `button memory-chip${c.ok === false ? " failed" : ""}`);
    const args = c.arguments || {};
    if (c.name === "remember") {
      a.textContent = c.ok === false ? "Could not remember" : `Remembered: ${args.statement || "a fact"}`;
      a.href = "#memory";
    } else if (c.name === "recall") {
      a.textContent = `Looked in memory${args.query ? `: ${args.query}` : ""}`;
      a.href = "#memory";
    } else {
      a.textContent = args.confirm ? "Forgotten, as you confirmed" : "Waiting for your yes to forget";
      a.href = args.confirm ? "#memory" : "#home";
    }
    row.append(a);
  }
  return row;
}

export function transcriptItem(e) {
  if (e.kind === "note") {
    const li = el("li", "aside note", e.text);
    li.title = fmtTime(e.t);
    li.dataset.id = e.id;
    return li;
  }
  const you = e.kind === "heard";
  const li = el("li", `msg ${you ? "you" : "marvin"}${e.proactive ? " proactive" : ""}`);
  li.dataset.id = e.id;
  const bubble = el("div", "bubble");
  const goesOn = you && (e.continues || []).length > 0;
  const label = you ? (e.source === "typed" ? "YOU, TYPED" : goesOn ? "YOU, GOING ON" : "YOU")
    : e.proactive ? "MARVIN, ON HIS OWN" : "MARVIN";
  bubble.append(who(label, e));
  if (you && e.image) bubble.append(imageFigure(e.image));
  bubble.append(el("span", "text", e.text));
  li.append(bubble);
  if (you && e.joined > 1) li.title = `Said in ${e.joined} breaths, heard as one question`;
  if (you) return li;
  const chips = memoryChips(e);
  if (chips) li.append(chips);
  if (e.error) li.append(el("p", "err", e.hint || "The language model is not answering."));
  if (e.context || e.prompt || e.first_word_s != null || e.model || e.memory) {
    const meta = el("p", "meta");
    const btn = el("button", "insp-btn");
    btn.type = "button";
    const time = e.first_word_s != null ? ` · ${fmtSeconds(e.first_word_s).replace(".00 s", " s")}` : "";
    btn.textContent = `Why this answer${time}`;
    if (e.interrupted) meta.append(el("span", null, "interrupted"));
    btn.setAttribute("aria-haspopup", "dialog");
    btn.addEventListener("click", () => openInspector(e));
    meta.append(btn);
    li.append(meta);
  }
  return li;
}

function ignoredSummary(list) {
  if (list.length === 1) {
    const e = list[0];
    return e.text ? `Not answered: “${e.text}”` : "A sound, ignored";
  }
  const sounds = list.filter((e) => !e.text).length, sentences = list.length - sounds;
  if (!sentences) return `${plural(sounds, "sound", "sounds")} ignored`;
  if (!sounds) return `${plural(sentences, "sentence", "sentences")} not answered`;
  return `${plural(sentences, "sentence", "sentences")} and ${plural(sounds, "sound", "sounds")} not answered`;
}

function ignoredLine(e) {
  const li = el("li", "ig-item");
  li.dataset.id = e.id;
  const t = el("time", null, fmtTime(e.t));
  t.dateTime = new Date(e.t * 1000).toISOString();
  const what = e.text ? el("q", null, e.text) : el("span", "ig-sound", "a sound");
  li.append(t, what, el("span", "ig-why", e.reason || "not answered"));
  if (e.dbfs != null) li.append(el("span", "ig-level", `${Math.round(e.dbfs)} dBFS`.replace("-", "−")));
  return li;
}

class IgnoredGroup {
  constructor(first) {
    this.entries = [];
    this.li = el("li", "aside ig");
    this.li.dataset.id = first.id;
    this.btn = el("button", "ig-sum");
    this.btn.type = "button";
    this.label = el("span", "ig-label");
    this.reason = el("span", "ig-reason");
    this.btn.append(this.label, this.reason);
    this.btn.insertAdjacentHTML("beforeend", '<svg class="icon ig-chev" aria-hidden="true"><use href="#i-chevron"/></svg>');
    this.list = el("ol", "ig-list");
    this.list.id = `ig-${++seq}`;
    this.list.hidden = true;
    this.btn.setAttribute("aria-expanded", "false");
    this.btn.setAttribute("aria-controls", this.list.id);
    this.btn.addEventListener("click", () => {
      this.list.hidden = !this.list.hidden;
      this.btn.setAttribute("aria-expanded", String(!this.list.hidden));
      this.li.classList.toggle("open", !this.list.hidden);
    });
    this.li.append(this.btn, this.list);
    this.add(first, false);
  }

  add(e, grow = true) {
    this.entries.push(e);
    this.list.append(ignoredLine(e));
    this.label.textContent = ignoredSummary(this.entries);
    this.reason.textContent = this.entries.length === 1 ? ` · ${e.reason || "not answered"}` : "";
    this.btn.title = this.entries.length === 1 ? "Why it was not answered" : "Show each one and why";
    if (grow && !reduceMotion.matches) {
      this.li.classList.remove("grew");
      void this.li.offsetWidth;
      this.li.classList.add("grew");
    }
  }
}

/**
 * Turns entries, in order, into list items: pairs answers with what was heard, folds consecutive ignored entries.
 * `item(e)` returns {li, grouped, replaces}: grouped means `e` went into the ignored line already on screen;
 * `replaces` lists the ids of earlier entries whose bubbles this one takes the place of (remove them).
 */
export class ConvoBuilder {
  constructor() { this.reset(); }
  reset() { this.lastHeard = null; this.group = null; this.lastReply = null; }
  item(e) {
    if (e.kind === "ignored") {
      if (this.group) { this.group.add(e); return { li: this.group.li, grouped: true, replaces: [] }; }
      this.group = new IgnoredGroup(e);
      return { li: this.group.li, grouped: false, replaces: [] };
    }
    this.group = null;
    if (e.kind === "heard") this.lastHeard = e;
    else if (e.kind === "reply") {
      if (!e.proactive) {
        if (this.lastHeard && e.t - this.lastHeard.t < 300) e._heard = this.lastHeard;
        this.lastHeard = null;
      }
      this.lastReply = e;
    }
    return { li: transcriptItem(e), grouped: false, replaces: e.kind === "heard" ? e.replaces || [] : [] };
  }
}

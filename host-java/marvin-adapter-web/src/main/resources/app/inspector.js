// SPDX-License-Identifier: MIT
// The reply inspector: why Marvin said that. What it heard (and the raw transcript), where the time went, the
// tools it used, the memory that went into the question (the profile's version, each section with its budget,
// each candidate with its score, kept or not, the timings, Ollama's counts), what it knew of the moment, the
// model and the language, and the exact message sent to the model.

import { el, fmtSeconds, fmtTime, openDialog, plural } from "./core.js";

const LANG_NAMES = { fr: "French", en: "English", de: "German", es: "Spanish", it: "Italian", nl: "Dutch", pt: "Portuguese" };
// the stages between the end of your question and Marvin's first word, in order
const STAGES = [
  { key: "endpoint", label: "End of speech", hint: "the silence that tells Marvin you finished" },
  { key: "queue", label: "Backlog", hint: "audio waiting to be heard" },
  { key: "stt", label: "Recognition", hint: "speech to text" },
  { key: "llm_first_token", label: "Model", hint: "until the model's first word" },
  { key: "tools", label: "Tools", hint: "running the tools the model asked for", tool: true },
  { key: "llm_first_token_2", label: "Model (again)", hint: "until the model's first word, with the tools' results" },
  { key: "first_chunk", label: "First sentence", hint: "until the model finished a first clause" },
  { key: "tts", label: "Synthesis", hint: "turning it into speech" },
];
const SECTION_NAMES = { now: "Now", today: "Today so far", facts: "Facts" };

/** Durations of the stages drawn in the timing bar (seconds, in order, only those that took time). */
export function timingSegments(e) {
  const lat = e.latency || {};
  const out = [];
  const add = (key, v) => { if (v != null && v >= 0.005) out.push({ ...STAGES.find((st) => st.key === key), v }); };
  add("endpoint", lat.endpoint);
  if (lat.queue >= 0.05) add("queue", lat.queue);
  add("stt", lat.stt);
  add("llm_first_token", lat.llm_first_token);
  add("tools", lat.tools);
  add("llm_first_token_2", lat.llm_first_token_2);
  const before = (lat.llm_first_token || 0) + (lat.tools || 0) + (lat.llm_first_token_2 || 0);
  if (lat.first_chunk != null) add("first_chunk", lat.first_chunk - before);
  add("tts", lat.tts);
  return out;
}

/** "- fact" lines of the "now" block sent with the question. */
export function contextFacts(ctx) {
  return (ctx || "").split("\n").filter((l) => l.startsWith("- ")).map((l) => l.slice(2).trim());
}

function section(title) {
  const sec = el("section", "insp-sec");
  sec.append(el("h3", null, title));
  return sec;
}

function toolCallText(c) {
  const args = Object.entries(c.arguments || {}).map(([k, v]) => `${k}: ${JSON.stringify(v)}`);
  return `${c.name}(${args.join(", ")})`;
}

const UNIT_SUFFIXES = [["_c", "°C"], ["_kmh", "km/h"], ["_mm", "mm"], ["_percent", "%"], ["_s", "s"]];

/** A tool's result, for reading: JSON objects as "key value · key value", with their units. */
function toolResultText(text) {
  try {
    const o = JSON.parse(text);
    if (o && typeof o === "object" && !Array.isArray(o)) {
      return Object.entries(o).map(([k, v]) => {
        const u = UNIT_SUFFIXES.find(([suf]) => k.endsWith(suf) && typeof v === "number");
        const label = (u ? k.slice(0, -u[0].length) : k).replace(/_/g, " ");
        const value = typeof v === "object" ? JSON.stringify(v) : String(v);
        return `${label} ${value}${u ? ` ${u[1]}` : ""}`;
      }).join(" · ");
    }
  } catch (err) { /* plain text */ }
  return text;
}

function timingSection(e, heard) {
  const segs = timingSegments(e);
  if (!segs.length) return null;
  const sec = section("Where the time went");
  const total = e.first_word_s != null ? e.first_word_s : segs.reduce((a, x) => a + x.v, 0);
  const sum = Math.max(total, segs.reduce((a, x) => a + x.v, 0));
  const bar = el("div", "tbar");
  bar.setAttribute("role", "img");
  bar.setAttribute("aria-label", segs.map((x) => `${x.label} ${fmtSeconds(x.v)}`).join(", "));
  let shade = 0;
  const cls = segs.map((x) => (x.tool ? "tool" : `t${Math.min(5, shade++)}`));
  segs.forEach((x, i) => {
    const seg = el("span", `tseg ${cls[i]}`);
    seg.style.flexGrow = String(Math.max(0.001, x.v / sum));
    seg.title = `${x.label}: ${fmtSeconds(x.v)} (${x.hint})`;
    bar.append(seg);
  });
  const rest = total - segs.reduce((a, x) => a + x.v, 0);
  if (rest > 0.02) { const r = el("span", "tseg rest"); r.style.flexGrow = String(rest / sum); bar.append(r); }
  const legend = el("ul", "tlegend");
  segs.forEach((x, i) => {
    const li = el("li");
    li.append(el("i", `sw ${cls[i]}`), el("span", null, x.label), el("b", null, fmtSeconds(x.v)));
    li.title = x.hint;
    legend.append(li);
  });
  if (rest > 0.02) {
    const li = el("li");
    li.append(el("i", "sw rest"), el("span", null, "Other"), el("b", null, fmtSeconds(rest)));
    legend.append(li);
  }
  sec.append(bar, legend);
  if (e.first_word_s != null) {
    sec.append(el("p", "insp-total", `First word ${fmtSeconds(e.first_word_s)} after ${heard && heard.source === "typed" ? "you sent it" : "you stopped talking"}${(e.latency || {}).speculative ? " · recognition started during your pause" : ""}`));
  }
  return sec;
}

function toolsSection(calls, fillerAt) {
  const sec = section(calls.length === 1 ? "Tool used" : `Tools used (${calls.length})`);
  const ul = el("ul", "insp-tools");
  for (const c of calls) {
    const li = el("li");
    li.append(el("span", "dur", c.seconds != null ? fmtSeconds(c.seconds) : ""), el("code", null, toolCallText(c)));
    li.append(c.ok === false ? el("p", "res bad", `Error: ${c.error || "failed"}`) : el("p", "res", toolResultText(c.result || "")));
    ul.append(li);
  }
  sec.append(ul);
  if (fillerAt != null) sec.append(el("p", "insp-total", "Marvin said a few words while the tool ran, so there was no silence."));
  return sec;
}

function memorySection(m) {
  const sec = section("Memory in this answer");
  const head = [];
  if (m.profile) head.push(m.profile.version ? `Profile version ${m.profile.version} (${plural(m.profile.chars, "character", "characters")}, in the stable part of the prompt)` : "No profile yet");
  if (m.tokens != null) head.push(`about ${m.tokens} tokens of context`);
  if (head.length) sec.append(el("p", "small", head.join(" · ")));
  for (const s of m.sections || []) {
    const box = el("div", "mem-sec");
    const h = el("div", "mem-sec-head");
    h.append(el("span", null, SECTION_NAMES[s.name] || s.name), el("span", null, `${s.tokens} / ${s.budget} tokens`));
    box.append(h);
    const items = s.items || [];
    if (!items.length) box.append(el("p", "small", s.name === "facts" ? "No fact close enough to the question." : "Nothing."));
    else {
      const ul = el("ul", "mem-items");
      for (const it of items) {
        const li = el("li", it.kept ? "" : "dropped");
        li.append(el("span", null, it.text));
        if (s.name === "facts" || it.score) li.append(el("span", "score", it.kept ? `${it.score.toFixed(2)}` : `${it.score.toFixed(2)} · left out`));
        ul.append(li);
      }
      box.append(ul);
    }
    sec.append(box);
  }
  const t = m.timings || {};
  const bits = Object.entries(t).filter(([, v]) => v != null).map(([k, v]) => `${k.replace(/_/g, " ")} ${Math.round(v * 1000)} ms`);
  if (bits.length) sec.append(el("p", "mem-note", `Timings: ${bits.join(" · ")}`));
  if (m.usage) {
    const u = m.usage, parts = [];
    if (u.prompt_eval_count != null) parts.push(`${u.prompt_eval_count} prompt tokens evaluated`);
    if (u.prompt_eval_s != null) parts.push(`in ${Math.round(u.prompt_eval_s * 1000)} ms`);
    if (u.messages_reused != null) parts.push(`${plural(u.messages_reused, "message", "messages")} reused from the cache`);
    if (u.eval_count != null) parts.push(`${u.eval_count} tokens written`);
    if (u.load_s) parts.push(`model loaded in ${Math.round(u.load_s * 1000)} ms`);
    if (parts.length) sec.append(el("p", "mem-note", `Ollama: ${parts.join(", ")}`));
  }
  if (m.problem) sec.append(el("p", "mem-note bad", m.problem));
  return sec;
}

/** Fills the dialog with the inspector of the answer `e` (`e._heard`: the question it answered). */
export function openInspector(e) {
  const body = openDialog("Why Marvin said that", { label: `Answer · ${fmtTime(e.t)}`, wide: true });
  const heard = e._heard;
  if (heard) {
    const sec = section(heard.source === "typed" ? "You typed" : "Marvin heard");
    sec.append(el("p", "insp-quote", heard.text));
    const joined = e.joined || heard.joined;
    if (joined > 1) sec.append(el("p", "insp-raw muted", `Joined ${joined} utterances: you went on after a pause, so Marvin took it as one question.`));
    if (heard.raw && heard.raw !== heard.text) {
      const raw = el("p", "insp-raw");
      raw.append(el("span", "muted", "Transcript: "), el("q", null, heard.raw));
      sec.append(raw);
    }
    body.append(sec);
  }
  const answer = section("Marvin answered");
  answer.append(el("p", "insp-quote", e.text));
  body.append(answer);
  const timing = timingSection(e, heard);
  if (timing) body.append(timing);
  if (e.tools && e.tools.length) body.append(toolsSection(e.tools, (e.latency || {}).filler_start));
  if (e.memory) body.append(memorySection(e.memory));
  const facts = contextFacts(e.context);
  if (facts.length && !e.memory) {
    const sec = section("What Marvin knew");
    const ul = el("ul", "insp-facts");
    for (const f of facts) ul.append(el("li", null, f));
    sec.append(ul);
    body.append(sec);
  }
  const foot = [e.model, LANG_NAMES[e.language] || e.language].filter(Boolean);
  if (e.interrupted) foot.push("interrupted");
  if (foot.length) body.append(el("p", "insp-foot", foot.join(" · ")));
  if (e.prompt) {
    const d = el("details", "insp-prompt");
    d.append(el("summary", null, "The exact message sent to the model"), el("pre", null, e.prompt));
    body.append(d);
  }
  if (!e.prompt && !e.context && !e.tools && e.first_word_s == null) {
    body.append(el("p", "insp-foot", e.proactive ? "Marvin said this on his own, without the language model." : "Nothing more is known about this answer."));
  }
}

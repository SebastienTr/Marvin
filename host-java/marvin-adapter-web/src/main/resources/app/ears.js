// SPDX-License-Identifier: MIT
// Marvin > Voice & models, "Your voice": the voice calibration (docs/voice.md, "Your voice"). Marvin measures the
// room and the microphone, hears a person read three short sentences, shows what it understood, and keeps their
// voiceprint on this computer. Also offered once on the first run, when the voice is on and nobody is known yet.

import { $, app, el, api, post, on, openDialog, closeDialog, toast } from "./core.js";

const OFFERED = "marvin.ears.offered";
const ACTIVE = new Set(["starting", "noise", "sentence", "heard", "retry"]);
const EARS = { "silero-vad": "Silero VAD", "smart-turn": "Smart Turn (end of a question)", speaker: "Speaker recognition", parakeet: "Parakeet (speech recognition)" };

let enroll = null;          // GET /api/voice/enroll
let poll = 0;
let dialogOpen = false;
let meter = null;           // the dialog's level bar, while it is open
let options = null;         // /api/voice/options (the ears' models)

const db = (x) => `${Math.round(x)} dBFS`;
const pct = (x) => `${Math.round((x || 0) * 100)} %`;

function safeStorage(fn) {
  try { return fn(window.localStorage); } catch { return null; }
}

async function load() {
  try {
    enroll = await api("/api/voice/enroll");
  } catch {
    enroll = null;
  }
  renderPanel();
  return enroll;
}

// ------------------------------------------------------------------ the panel

function renderPanel() {
  const panel = $("ears-panel");
  if (!panel) return;
  const pill = $("ears-pill"), people = $("ears-people"), cal = $("ears-calibration");
  const voiceOn = app.voice && app.voice.state === "on";
  $("ears-start").disabled = !voiceOn;
  $("ears-start-hint").textContent = voiceOn ? "" : "Turn Marvin’s voice on to calibrate.";
  if (!enroll) {
    pill.textContent = "–";
    people.replaceChildren();
    cal.textContent = "Not available while the voice settings cannot be read.";
    return;
  }
  const known = enroll.people || [];
  pill.textContent = known.length ? `${known.length} voice${known.length === 1 ? "" : "s"}` : "Not calibrated";
  pill.className = `pill${known.length ? "" : " attention-pill"}`;
  people.replaceChildren(...known.map((p) => {
    const li = el("li");
    const who = el("span", null, p.name);
    const when = el("span", "small muted", p.created ? `${p.created.replace("T", " ").slice(0, 16)} · ${p.device}` : p.device);
    const del = el("button", "link-button", "Delete");
    del.type = "button";
    del.addEventListener("click", () => forget(p.name));
    const right = el("span", "inline");
    right.append(when, del);
    li.append(who, right);
    return li;
  }));
  if (!known.length) people.append(el("li", "small muted", "Marvin knows no voice yet: it answers anyone and cannot tell who asks."));
  const c = enroll.calibration || {};
  const lines = Object.entries(c).map(([kind, x]) =>
    `${kind === "robot" ? "Robot" : "This computer"}: noise ${db(x.noise_dbfs)}, voice ${db(x.speech_dbfs)} (${String(x.at || "").replace("T", " ").slice(0, 16)})`);
  cal.textContent = lines.length ? lines.join(" · ") : "The microphone is not calibrated: Marvin uses its default levels.";
  renderModels();
}

function renderModels() {
  const list = $("ears-models");
  const ears = (options && options.ears) || [];
  list.hidden = !ears.length;
  list.replaceChildren(...ears.map((e) => {
    const li = el("li");
    li.append(el("span", null, EARS[e.name] || e.name), el("span", `small ${e.installed ? "muted" : "bad"}`, e.installed ? "ready" : e.why || "missing"));
    return li;
  }));
}

async function forget(name) {
  if (!window.confirm(`Delete ${name}’s voiceprint? Marvin will no longer recognise that voice.`)) return;
  try {
    enroll = await post("/api/voice/people/forget", { name });
    renderPanel();
    toast(`${name}’s voiceprint deleted.`);
  } catch (e) {
    toast(`Not deleted: ${e.message}`);
  }
}

// ------------------------------------------------------------------ the dialog

function intro(body, firstRun) {
  body.replaceChildren();
  body.append(el("p", null, firstRun
    ? "Marvin can learn your voice now: about thirty seconds. It measures how quiet your room is and how loud you speak through this microphone, then you read three short sentences, in French and English."
    : "Marvin measures how quiet your room is and how loud you speak through the voice’s microphone, then you read three short sentences, in French and English."));
  body.append(el("p", null, "It then knows when you have finished talking, hears names right, and can tell who is speaking (“only answer voices Marvin knows” is a setting). Your voiceprint stays on this computer; you can delete it here at any time."));
  const label = el("label", null, "Your name");
  label.htmlFor = "ears-name";
  const input = el("input");
  input.id = "ears-name";
  input.type = "text";
  input.maxLength = 40;
  input.autocomplete = "given-name";
  input.value = (enroll && enroll.name) || "";
  const err = el("p", "form-error");
  err.hidden = true;
  const actions = el("div", "actions");
  const later = el("button", null, firstRun ? "Later" : "Cancel");
  later.type = "button";
  later.addEventListener("click", closeDialog);
  const start = el("button", "primary", "Start");
  start.type = "button";
  start.addEventListener("click", async () => {
    err.hidden = true;
    try {
      enroll = await post("/api/voice/enroll", { name: input.value.trim() });
      render();
      tick();
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
  input.addEventListener("keydown", (ev) => { if (ev.key === "Enter") start.click(); });
  actions.append(later, start);
  body.append(label, input, err, actions);
  setTimeout(() => input.focus(), 50);
}

function levelBar() {
  const wrap = el("div", "ears-meter");
  const bar = el("span");
  wrap.append(bar);
  wrap.setAttribute("role", "meter");
  wrap.setAttribute("aria-label", "Microphone level");
  meter = bar;
  return wrap;
}

function render() {
  const body = $("dialog-body");
  if (!dialogOpen || !enroll) return;
  const s = enroll.state;
  if (s === "idle" || s === "cancelled") { intro(body, false); return; }
  body.replaceChildren();
  const sentences = enroll.sentences || [];
  const steps = el("ol", "ears-steps");
  sentences.forEach((text, i) => {
    const heard = (enroll.heard || []).find((h) => h.index === i);
    const li = el("li", heard ? "done" : i === enroll.index && (s === "sentence" || s === "retry") ? "now" : "");
    li.append(el("span", "ears-sentence", text));
    if (heard) li.append(el("span", "small muted", `I heard: “${heard.text}” · ${pct(heard.match)} · ${db(heard.dbfs)}`));
    steps.append(li);
  });
  if (s === "starting" || s === "noise") {
    body.append(el("p", "ears-now", "Stay quiet for a moment: Marvin listens to the room."), levelBar(), steps);
  } else if (s === "sentence" || s === "heard" || s === "retry") {
    const now = sentences[enroll.index] || "";
    body.append(el("p", "ears-now", "Read this aloud, as you would talk to Marvin:"), el("p", "ears-big", now), levelBar());
    if (enroll.message) body.append(el("p", "form-error", enroll.message));
    body.append(steps);
  } else if (s === "done") {
    const r = enroll.result || {};
    body.append(el("p", "ears-now", r.voiceprint ? `Marvin knows your voice, ${enroll.name}.` : "The microphone is calibrated."), steps);
    const dl = el("dl", "ears-result");
    const row = (k, v) => { dl.append(el("dt", null, k), el("dd", null, v)); };
    row("Room noise", db(r.noise_dbfs));
    row("Your voice", `${db(r.speech_dbfs)} (${Math.round(r.speech_dbfs - r.noise_dbfs)} dB above the noise)`);
    row("Speech starts above", db(r.vad_min_dbfs));
    row("Too quiet below", db(r.speech_min_dbfs));
    row("Speech detector threshold", (r.vad_threshold || 0.5).toFixed(2));
    body.append(dl);
    for (const w of r.warnings || []) body.append(el("p", "form-error", w));
  } else if (s === "failed") {
    body.append(el("p", "form-error", enroll.message || "The calibration stopped."), steps);
  }
  const actions = el("div", "actions");
  if (ACTIVE.has(s)) {
    const cancel = el("button", null, "Stop");
    cancel.type = "button";
    cancel.addEventListener("click", async () => { enroll = await post("/api/voice/enroll/cancel").catch(() => enroll); render(); });
    actions.append(cancel);
  } else {
    const again = el("button", null, s === "done" ? "Calibrate again" : "Try again");
    again.type = "button";
    again.addEventListener("click", () => intro(body, false));
    const done = el("button", "primary", "Done");
    done.type = "button";
    done.addEventListener("click", closeDialog);
    actions.append(again, done);
  }
  body.append(actions);
}

async function tick() {
  clearTimeout(poll);
  if (!dialogOpen) return;
  try {
    enroll = await api("/api/voice/enroll");
  } catch {
    // the host restarts the voice after a calibration: try again
  }
  render();
  if (enroll && ACTIVE.has(enroll.state)) poll = setTimeout(tick, 400);
  else renderPanel();
}

export async function openCalibration({ firstRun = false } = {}) {
  await load();
  openDialog("Your voice", { label: "Marvin · Voice calibration" });
  dialogOpen = true;
  const d = $("dialog");
  const closed = () => {
    dialogOpen = false;
    meter = null;
    clearTimeout(poll);
    d.removeEventListener("close", closed);
    load();
  };
  d.addEventListener("close", closed);
  if (enroll && ACTIVE.has(enroll.state)) { render(); tick(); } else intro($("dialog-body"), firstRun);
}

/** Once, when the voice runs and Marvin knows nobody and nothing is calibrated. */
async function maybeOffer() {
  if (safeStorage((s) => s.getItem(OFFERED)) || !app.voice || app.voice.state !== "on") return;
  const e = await load();
  if (!e || !e.first_run || dialogOpen || $("dialog").open) return;
  safeStorage((s) => s.setItem(OFFERED, "1"));
  openCalibration({ firstRun: true });
}

export function setOptions(o) {
  options = o;
  renderModels();
}

export function setup() {
  $("ears-start").addEventListener("click", () => openCalibration());
  on("level", (l) => { if (meter) meter.style.width = `${Math.round(Math.min(1, l.mic || 0) * 100)}%`; });
  on("voice", (v) => {
    renderPanel();
    if (v && v.state === "on") setTimeout(maybeOffer, 1500);
  });
  on("view", ({ view, sub }) => { if (view === "marvin" && sub === "voice") load(); });
}

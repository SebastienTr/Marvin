// SPDX-License-Identifier: MIT
// Talk: the live conversation. The voice's live signals drive it while you talk: "level" (the microphone,
// ~16 Hz), "utterance" (someone talks, stops, is judged), "partial" (the words understood so far) and "say" (a
// piece of the reply as it goes to the speaker). Your bubble forms while you speak and Marvin's writes itself as
// he says it; the listening strip and Marvin's face follow the microphone, or Marvin's voice while he speaks.
// Voice on and off, Talk now, Mute, Stop, typed questions (with an image: attach.js), and beside it what Marvin
// knows right now and the memory its last answer used.

import { $, app, el, api, post, on, emit, fmtDuration, fmtTime, fmtSeconds, cssVar, reduceMotion, toast } from "./core.js";
import { Face, setLevelSource } from "./face.js";
import { ConvoBuilder } from "./convo.js";
import { openInspector } from "./inspector.js";
import { setup as setupAttach, settled as imageSettled } from "./attach.js";

const WAVE_BARS = 56;
const chat = new ConvoBuilder();
const ids = new Set();
let lastReply = null;
let follow = true;
let talkNowAt = 0;

const live = {
  levels: new Array(WAVE_BARS).fill(0),
  lastPush: 0,
  mic: 0, micAt: 0,
  speech: false,
  lvl: 0,
  you: null,
  marvin: null,
  mouth: [],
  mode: "off",
  closingUntil: 0,
  raf: 0,
  colors: null,
  heardUid: null,
};

// ------------------------------------------------------------------ scrolling

function nearBottom() {
  const t = $("transcript");
  return t.scrollHeight - t.scrollTop - t.clientHeight < 80;
}

/** Follows the conversation unless you scrolled up to read (then it stays put until you come back). */
function watchScroll() {
  const t = $("transcript");
  let timer;
  const check = () => { clearTimeout(timer); timer = setTimeout(() => { follow = nearBottom(); }, 120); };
  for (const ev of ["wheel", "touchmove", "keydown", "pointerdown"]) t.addEventListener(ev, check, { passive: true });
  if (window.ResizeObserver) new ResizeObserver(() => { if (follow) t.scrollTop = t.scrollHeight; }).observe(t);
}

function scrollTranscript(force, smooth = true) {
  const t = $("transcript");
  if (force) follow = true;
  if (follow) requestAnimationFrame(() => t.scrollTo({ top: t.scrollHeight, behavior: smooth && !reduceMotion.matches ? "smooth" : "auto" }));
}

// ------------------------------------------------------------------ entries

function addTranscript(e, { fresh = false } = {}) {
  if (ids.has(e.id)) return;
  ids.add(e.id);
  if (fresh) earconFor(e);
  const ol = $("transcript");
  if (e.kind === "ignored" && live.you) { dissolve(live.you.li); live.you = null; }
  const { li, grouped, replaces } = chat.item(e);
  // one thought said in several breaths: the question that goes on takes the place of its first part
  for (const id of replaces || []) dissolve(ol.querySelector(`:scope > li[data-id="${id}"]`));
  $("transcript-empty").hidden = true;
  if (!grouped) {
    const pending = e.kind === "heard" && e.source !== "typed" ? live.you : e.kind === "reply" ? live.marvin : null;
    if (pending) settle(pending, li);
    else {
      if (!fresh) li.classList.add("settled");
      ol.appendChild(li);
    }
    while (ol.children.length > 200) ol.firstElementChild.remove();
  }
  if (e.kind === "reply" && !e.proactive) { lastReply = e; renderLastAnswer(); }
  scrollTranscript(false, !!fresh);
}

function liveItem(side, extra) {
  const li = el("li", `msg ${side} live${extra ? " " + extra : ""}`);
  li.setAttribute("aria-hidden", "true");
  $("transcript").appendChild(li);
  $("transcript-empty").hidden = true;
  return li;
}

function waveEl() {
  const w = el("span", "vu");
  for (let i = 0; i < 5; i++) w.appendChild(el("i"));
  return w;
}

/** The live bubble `l` becomes the entry `li` (same place, same text: no jump). */
function settle(l, li) {
  if (l.timers) l.timers.forEach(clearTimeout);
  li.classList.add("settled", "just-settled");
  l.li.replaceWith(li);
  if (l === live.you) live.you = null;
  if (l === live.marvin) { live.marvin = null; live.mouth = []; }
  setTimeout(() => li.classList.remove("just-settled"), 600);
}

function dissolve(li) {
  if (!li || !li.isConnected) return;
  if (reduceMotion.matches) { li.remove(); return; }
  li.style.height = `${li.offsetHeight}px`;
  li.classList.add("dissolving");
  void li.offsetHeight;
  li.style.height = "0px";
  setTimeout(() => li.remove(), 380);
}

/** Your bubble only appears when Marvin is listening to you (a listening window, or no wake word), or once the
 *  words so far start with "Marvin". It waits 300 ms, so a click or a cough never flashes a bubble. */
function listeningToYou() {
  const v = app.voice;
  return !!v && (v.status === "listening" || !v.wake);
}

function youBubble(uid) {
  if (live.you && live.you.uid === uid) return live.you;
  if (live.you) dissolve(live.you.li);
  const li = liveItem("you");
  const b = el("p", "bubble");
  b.append(el("span", "who", "YOU"), waveEl(), el("span", "words"));
  li.appendChild(b);
  live.you = { li, uid };
  scrollTranscript(true);
  return live.you;
}

function onUtterance(u) {
  if (u.state === "start") {
    clearTimeout(live.youTimer);
    live.heardUid = u.uid;
    live.youTimer = setTimeout(() => { if (live.heardUid === u.uid && listeningToYou()) youBubble(u.uid); }, 300);
  } else if (u.state === "end") {
    if (live.heardUid === u.uid && listeningToYou()) youBubble(u.uid);
    if (live.you && live.you.uid === u.uid) live.you.li.classList.add("understanding");
    labelLive();
  } else if (u.state === "done") {
    clearTimeout(live.youTimer);
    live.heardUid = null;
    const l = live.you;
    if (l && l.uid === u.uid) setTimeout(() => { if (live.you === l) { dissolve(l.li); live.you = null; } }, 250);
    labelLive();
  }
}

function onPartial(p) {
  if (!(live.you && live.you.uid === p.uid)) {
    if (live.heardUid !== p.uid || !(listeningToYou() || /^\W*marvin\b/i.test(p.text))) return;
    youBubble(p.uid);
  }
  const w = live.you.li.querySelector(".words");
  if (w.textContent === p.text) return;
  w.textContent = p.text;
  w.classList.remove("fresh-words");
  void w.offsetWidth;
  w.classList.add("fresh-words");
  scrollTranscript(false);
}

function marvinBubble() {
  if (live.marvin && live.marvin.said) return live.marvin;
  const li = live.marvin ? live.marvin.li : liveItem("marvin");
  li.classList.remove("thinking");
  li.textContent = "";
  const b = el("div", "bubble");
  const said = el("span", "said");
  b.append(el("span", "who", "MARVIN"), said);
  li.appendChild(b);
  live.marvin = { li, said, timers: [], end: 0 };
  return live.marvin;
}

function onSay(s) {
  if (!(live.marvin && live.marvin.said)) earcons.play("reply");
  const m = marvinBubble();
  m.li.classList.add("speaking");
  const now = performance.now() / 1000;
  const t0 = Math.max(now + 0.08, m.end);
  const seconds = Math.max(0.2, s.seconds || 0.2);
  m.end = t0 + seconds;
  live.mouth.push({ t0, seconds, env: s.envelope || [] });
  const words = (s.text || "").split(/\s+/).filter(Boolean);
  words.forEach((w, i) => {
    const at = (t0 + (seconds * 0.92 * i) / Math.max(1, words.length) - now) * 1000;
    m.timers.push(setTimeout(() => {
      if (m.said.childNodes.length) m.said.append(" ");
      m.said.appendChild(el("span", "w", w));
      scrollTranscript(false, false);
    }, Math.max(0, at)));
  });
  ensureLoop();
}

function thinkingBubble(onOff) {
  if (onOff && !live.marvin) {
    const li = liveItem("marvin", "thinking");
    const b = el("div", "bubble");
    const dots = el("span", "dots");
    for (let i = 0; i < 3; i++) dots.appendChild(el("i"));
    b.append(el("span", "who", "MARVIN"), dots);
    li.appendChild(b);
    live.marvin = { li, said: null, timers: [], end: 0 };
    scrollTranscript(false);
  } else if (!onOff && live.marvin && !live.marvin.said) {
    dissolve(live.marvin.li);
    live.marvin = null;
  }
}

function mouthLevel(now) {
  for (const m of live.mouth) {
    if (now >= m.t0 && now < m.t0 + m.seconds) return m.env[Math.floor((now - m.t0) * 20)] || 0;
  }
  return 0;
}

// ------------------------------------------------------------------ the listening strip

function liveMode(v) {
  if (!v || v.state !== "on") return v && v.state === "starting" ? "starting" : "off";
  if (v.muted && v.status !== "speaking" && v.status !== "thinking") return "muted";
  return v.status;
}

const LISTEN_LABELS = {
  starting: "Waking up…", muted: "Microphone muted", listening: "Ask your question…", thinking: "Thinking…", speaking: "Marvin is speaking",
};

function setFading(node, text) {
  if (node.textContent === text) return;
  node.textContent = text;
  node.classList.remove("fade-in");
  void node.offsetWidth;
  node.classList.add("fade-in");
}

function updateLive(v) {
  const mode = liveMode(v);
  if (mode === "listening" && v.listen_s != null && !v.hearing) {
    live.frozenLeft = null;
    const now = performance.now() / 1000;
    if (live.mode !== "listening" || !live.windowEnd) live.windowTotal = Math.max(1, v.listen_s);
    live.windowEnd = now + v.listen_s;
  } else if (mode !== "listening") {
    live.windowEnd = 0;
  }
  const was = live.mode;
  live.mode = mode;
  if (was !== "listening" && mode === "listening") {
    live.windowKind = was === "speaking" || was === "thinking" ? "follow-up" : "asked";
    // the voice chimes on the computer's speaker when a window opens; the browser adds its own note only when
    // this page pressed Talk now, or when the voice's chime is off
    if (live.windowKind === "asked" && (performance.now() - talkNowAt < 3000 || v.chime === false)) earcons.play("open");
  }
  if (was === "listening" && mode === "idle") {
    live.closingUntil = performance.now() + 1600;
    if (live.windowKind === "asked" && performance.now() - earcons.lastMiss > 800) earcons.play("close");
  }
  if (mode !== "idle") live.closingUntil = 0;
  $("listen").dataset.mode = mode;
  labelLive();
  if (mode === "thinking") thinkingBubble(true);
  else if (mode !== "speaking") thinkingBubble(false);
  if (mode !== "speaking" && live.marvin && live.marvin.said) live.marvin.li.classList.remove("speaking");
  if ((mode === "off" || mode === "muted") && live.you) { dissolve(live.you.li); live.you = null; }
  ensureLoop();
}

function labelLive() {
  const strip = $("listen");
  let text = LISTEN_LABELS[live.mode] || "";
  if (live.mode === "idle") {
    if (live.closingUntil > performance.now()) text = "Stopped listening";
    else text = app.voice && app.voice.wake ? "Say “Marvin, …”" : "Waiting for you to speak";
  }
  if (live.speech && (live.mode === "idle" || live.mode === "listening")) text = live.mode === "listening" ? "Listening…" : "Hearing something…";
  if (live.heardUid != null && !live.speech && live.mode !== "thinking" && live.mode !== "speaking") text = "Understanding…";
  strip.classList.toggle("closing", live.closingUntil > performance.now());
  // while you talk (or your words are being understood) the listening window waits: no draining bar
  strip.classList.toggle("hearing", !!live.speech || (live.mode === "listening" && hearing()));
  setFading($("listen-label"), text);
}

/** Someone talks in the listening window, or their words are being understood: it does not run out. */
function hearing() {
  return !!live.speech || live.heardUid != null || !!(app.voice && app.voice.hearing);
}

function onLevel(l) {
  live.mic = l.mic || 0;
  live.micAt = performance.now();
  if (live.speech !== !!l.speech) { live.speech = !!l.speech; labelLive(); }
  ensureLoop();
}

function ensureLoop() {
  if (live.raf || document.hidden) return;
  live.raf = requestAnimationFrame(frame);
}

function frame(ts) {
  live.raf = 0;
  const now = ts / 1000;
  const speaking = live.mode === "speaking";
  let target = speaking ? mouthLevel(now) : live.mic;
  if (!speaking && performance.now() - live.micAt > 250) target = 0;
  if (live.mode === "off" || live.mode === "muted" || live.mode === "starting") target = 0;
  live.lvl += (target - live.lvl) * (target > live.lvl ? 0.55 : 0.18);
  if (ts - live.lastPush > 55) {
    live.levels.push(live.lvl);
    live.levels.shift();
    live.lastPush = ts;
  }
  const lv = live.lvl.toFixed(3);
  if (live.windowEnd) {
    if (hearing()) {
      // frozen at what was left when they started talking (the voice sends the time left once they are done)
      if (live.frozenLeft == null) live.frozenLeft = Math.max(0, live.windowEnd - now);
      live.windowEnd = now + live.frozenLeft;
    } else {
      live.frozenLeft = null;
    }
    const remain = Math.max(0, Math.min(1, (live.windowEnd - now) / live.windowTotal));
    $("listen").style.setProperty("--remain", remain.toFixed(3));
  }
  if (live.you) live.you.li.style.setProperty("--lvl", live.you.li.classList.contains("understanding") ? 0 : lv);
  if (live.marvin && live.marvin.said) live.marvin.li.style.setProperty("--lvl", lv);
  drawListenWave(ts);
  if (live.closingUntil && live.closingUntil <= performance.now()) { live.closingUntil = 0; labelLive(); }
  const moving = live.lvl > 0.004 || live.levels.some((x) => x > 0.004) || live.closingUntil;
  if (moving || ["idle", "listening", "thinking", "speaking", "starting"].includes(live.mode)) ensureLoop();
}

function drawListenWave(ts) {
  const c = $("listen-wave");
  const w = c.clientWidth, h = c.clientHeight;
  if (!w || !h) return;
  const dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  if (!live.colors) live.colors = { accent: cssVar("--accent"), text: cssVar("--ink"), muted: cssVar("--muted") };
  const g = c.getContext("2d");
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  const mode = live.mode;
  const color = mode === "speaking" ? live.colors.text : mode === "listening" || live.speech ? live.colors.accent : live.colors.muted;
  const step = w / WAVE_BARS;
  const bw = Math.max(2, Math.min(3, step * 0.45));
  const shift = reduceMotion.matches ? 0 : Math.min(1, (ts - live.lastPush) / 55) * step;
  g.fillStyle = color;
  for (let i = 0; i < WAVE_BARS; i++) {
    let v = live.levels[i];
    if (mode === "thinking") v = 0.08 + 0.08 * Math.sin(ts / 260 - i * 0.35);
    const age = i / (WAVE_BARS - 1);
    const bh = Math.max(bw, v * (h - 4));
    const x = i * step - shift + (step - bw) / 2;
    g.globalAlpha = (mode === "idle" && !live.speech ? 0.45 : 1) * (0.15 + 0.85 * age);
    const r = bw / 2, y = (h - bh) / 2;
    g.beginPath();
    g.moveTo(x + r, y);
    g.arcTo(x + bw, y, x + bw, y + bh, r);
    g.arcTo(x + bw, y + bh, x, y + bh, r);
    g.arcTo(x, y + bh, x, y, r);
    g.arcTo(x, y, x + bw, y, r);
    g.fill();
  }
  g.globalAlpha = 1;
}

// ------------------------------------------------------------------ the voice

function renderVoice(v) {
  const on = v.state === "on";
  let text;
  if (v.state === "unavailable") text = "The voice is not available on this host";
  else if (v.state === "off") text = "Voice off";
  else if (v.state === "starting") text = "Starting, loading the models…";
  else if (v.state === "stopping") text = "Stopping…";
  else if (v.state === "error") text = "Voice unavailable";
  else if (v.muted && v.status === "idle") text = "Microphone muted";
  else text = { idle: v.wake ? "Ready: say “Marvin, …”" : "Ready: just talk", listening: "Listening: ask your question", thinking: "Thinking…", speaking: "Speaking…" }[v.status] || v.status;
  setFading($("voice-status"), text);
  $("mini-face").dataset.status = on ? v.status : v.state;
  $("talk-title").textContent = on ? (v.status === "speaking" ? "I’m speaking." : v.status === "thinking" ? "Let me think." : "I’m listening.")
    : v.state === "starting" ? "Waking up." : v.state === "error" ? "I can’t hear you." : "Resting.";

  const sw = $("voice-switch");
  const lit = v.state === "on" || v.state === "starting";
  sw.setAttribute("aria-pressed", String(lit));
  $("voice-switch-label").textContent = lit ? "Voice on" : "Voice off";
  sw.title = lit ? "Turn Marvin’s voice off" : "Turn Marvin’s voice on";
  sw.disabled = v.state === "unavailable" || v.state === "stopping";

  $("voice-notice").hidden = v.state !== "error";
  if (v.state === "error") {
    $("voice-notice-title").textContent = v.error;
    $("voice-notice-fix").hidden = !v.fix;
    $("voice-notice-code").textContent = v.fix || "";
  }
  for (const id of ["listen-now", "mute", "ask-input", "ask-send"]) $(id).disabled = !on;
  $("stop-speaking").disabled = !on || !(v.status === "thinking" || v.status === "speaking");
  const listening = on && v.status === "listening";
  $("listen-now").setAttribute("aria-pressed", String(listening));
  $("listen-now-label").textContent = listening ? "Listening" : "Talk now";
  $("listen-now").title = listening ? "Marvin is listening: ask your question. Press to stop listening" : "Ask a question without saying “Marvin”";
  $("mute").setAttribute("aria-pressed", String(!!v.muted));
  $("mute-label").textContent = v.muted ? "Unmute" : "Mute";
  $("mute").title = v.muted ? "The microphone is muted: press to unmute" : "Mute the microphone";
  $("ask-input").placeholder = on ? "Write to Marvin…" : "Turn the voice on to write to Marvin";
  if (!ids.size) {
    $("transcript-empty").hidden = false;
    $("transcript-empty").firstElementChild.textContent = on
      ? (v.wake ? "Say “Marvin, …” or write below." : "Just talk, or write below.")
      : v.state === "unavailable" ? "The conversation appears here when Marvin’s voice runs." : "Turn the voice on to talk to Marvin.";
  }
  updateLive(v);
}

async function voiceCommand(path, body) {
  try {
    const r = await post(path, body);
    if (r.voice) emit("voice", r.voice);
  } catch (e) {
    addTranscript({ id: `local-${Date.now()}`, kind: "note", text: e.message, t: Date.now() / 1000 });
  }
}

// ------------------------------------------------------------------ sounds (earcons)
// Short notes synthesised by this browser (WebAudio, no files): listening opens (two rising notes), closes
// without a question (two falling notes), not understood while listening (a low blip), an answer starts (a
// faint tick). Off in Marvin > Preferences. Nothing plays before a first click or key press on the page.

const earcons = {
  ctx: null,
  lastMiss: 0,
  unlock() {
    if (!this.ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      try { this.ctx = new AC(); } catch (e) { return; }
    }
    if (this.ctx.state === "suspended") this.ctx.resume().catch(() => {});
  },
  tone(f0, f1, at, dur, gain, type = "sine") {
    const c = this.ctx, o = c.createOscillator(), g = c.createGain();
    o.type = type;
    o.frequency.setValueAtTime(f0, at);
    if (f1 !== f0) o.frequency.exponentialRampToValueAtTime(f1, at + dur);
    g.gain.setValueAtTime(0.0001, at);
    g.gain.linearRampToValueAtTime(gain, at + 0.012);
    g.gain.exponentialRampToValueAtTime(0.0001, at + dur);
    o.connect(g).connect(c.destination);
    o.start(at);
    o.stop(at + dur + 0.02);
  },
  play(name) {
    if (app.settings.ui_sounds === false || !this.ctx || this.ctx.state !== "running") return;
    const t = this.ctx.currentTime + 0.01;
    if (name === "open") { this.tone(587.3, 587.3, t, 0.14, 0.05); this.tone(880, 880, t + 0.09, 0.2, 0.045); }
    else if (name === "close") { this.tone(783.99, 783.99, t, 0.14, 0.035); this.tone(523.25, 523.25, t + 0.1, 0.24, 0.03); }
    else if (name === "miss") { this.lastMiss = performance.now(); this.tone(196, 164.8, t, 0.13, 0.06, "triangle"); }
    else if (name === "reply") this.tone(1318.5, 1318.5, t, 0.07, 0.014);
  },
};
export { earcons };

function earconFor(e) {
  if (e.kind === "ignored" && live.mode === "listening") earcons.play("miss");
}

function setupEarcons() {
  const unlock = () => {
    earcons.unlock();
    if (earcons.ctx) for (const ev of ["pointerdown", "keydown"]) document.removeEventListener(ev, unlock, true);
  };
  for (const ev of ["pointerdown", "keydown"]) document.addEventListener(ev, unlock, true);
}

// ------------------------------------------------------------------ what Marvin knows now

function nowPart(text, cls, priority = 0) {
  const sp = el("span", `now-part${cls ? " " + cls : ""}`, text);
  sp.dataset.p = priority;
  return sp;
}

function renderNow(s) {
  const line = $("now-line"), box = $("now-items");
  const parts = [], said = [];
  let lead, detail = [];
  if (!s.online) { lead = "Robot not connected"; said.push("the robot is not connected"); }
  else if (!s.present) { lead = "Nobody in front of Marvin"; said.push("nobody in front of Marvin"); }
  else {
    lead = s.seated ? (s.seated_s < 60 ? "Just sat down" : `Seated ${fmtDuration(s.seated_s)}`) : "You’re here";
    said.push(s.seated ? `you have been seated for ${fmtDuration(s.seated_s)}` : "you are here");
    if (s.distance_m != null) { parts.push(nowPart(`${s.distance_m.toFixed(1)} m`, "", 4)); detail.push(`${s.distance_m.toFixed(1)} m away`); said.push(`${s.distance_m.toFixed(1)} metres away`); }
    if (s.breath_rate != null) { parts.push(nowPart(`breathing ${Math.round(s.breath_rate)}/min`, "", 3)); detail.push(`breathing ${Math.round(s.breath_rate)}/min`); said.push(`breathing ${Math.round(s.breath_rate)} per minute`); }
    if (s.heart_rate != null) { parts.push(nowPart(`heart ${Math.round(s.heart_rate)}/min`, "", 2)); detail.push(`heart ${Math.round(s.heart_rate)}/min`); said.push(`heart ${Math.round(s.heart_rate)} per minute`); }
  }
  parts.unshift(nowPart(lead, "lead"));
  if (s.simulated) { parts.push(nowPart("simulated", "sim", 1)); said.push("simulated sensors"); detail.unshift("Simulated sensors"); }
  const sig = parts.map((x) => x.className + x.textContent).join("|");
  if (sig !== line.dataset.sig) {
    line.dataset.sig = sig;
    box.replaceChildren(...parts);
    fitNow();
  }
  line.hidden = false;
  line.setAttribute("aria-label", `Marvin knows: ${said.join(", ")}. Open the robot's sensors`);
  $("ctx-presence").textContent = s.status;
  $("ctx-presence-detail").textContent = detail.join(" · ");
}

/** Hides the least useful parts (distance, then breathing, heart, the simulated mark) until the line fits. */
function fitNow() {
  const box = $("now-items");
  const parts = [...box.children];
  parts.forEach((x) => { x.hidden = false; });
  if (!box.clientWidth) return;
  const order = parts.filter((x) => +x.dataset.p > 0).sort((a, b) => b.dataset.p - a.dataset.p);
  for (const x of order) {
    if (box.scrollWidth <= box.clientWidth + 1) break;
    x.hidden = true;
  }
}

// ------------------------------------------------------------------ the last answer, beside the conversation

function renderLastAnswer() {
  const e = lastReply;
  const mem = $("ctx-memory"), last = $("ctx-last");
  $("open-last-inspector").disabled = !e;
  if (!e) return;
  const facts = e.memory ? (e.memory.sections || []).find((s) => s.name === "facts") : null;
  const kept = facts ? facts.items.filter((i) => i.kept) : [];
  if (kept.length) {
    const ul = el("ul", "ctx-facts");
    for (const i of kept.slice(0, 5)) ul.append(el("li", null, i.text));
    const a = el("a", "small quiet-link", "Review memory");
    a.href = "#memory";
    mem.replaceChildren(ul, a);
  } else if (e.memory) {
    mem.replaceChildren(el("span", "muted", e.memory.problem || "No remembered fact was close enough to the last question."));
  } else {
    mem.replaceChildren(el("span", "muted", "Memory was not used for the last answer."));
  }
  const parts = [fmtTime(e.t)];
  if (e.first_word_s != null) parts.push(`first word after ${fmtSeconds(e.first_word_s)}`);
  if (e.tools && e.tools.length) parts.push(`${e.tools.map((t) => t.name).join(", ")}`);
  if (e.model) parts.push(e.model);
  last.replaceChildren(el("span", null, parts.join(" · ")));
}

// ------------------------------------------------------------------ setup

export function setup() {
  watchScroll();
  setupEarcons();
  new Face($("mini-face-canvas"), { span: 300, labelled: $("mini-face-canvas") });
  setLevelSource(() => live.lvl);
  $("voice-switch").addEventListener("click", () => {
    const lit = $("voice-switch").getAttribute("aria-pressed") === "true";
    voiceCommand(lit ? "/api/voice/off" : "/api/voice/on");
  });
  $("voice-retry").addEventListener("click", () => voiceCommand("/api/voice/on"));
  $("listen-now").addEventListener("click", () => {
    const v = app.voice;
    if (!v || v.state !== "on") return;
    const stop = v.status === "listening";
    if (!stop) talkNowAt = performance.now();
    emit("voice", { ...v, status: stop ? "idle" : "listening", muted: false, listen_s: stop ? null : 6 });
    voiceCommand("/api/voice/listen", { on: !stop });
  });
  $("stop-speaking").addEventListener("click", () => voiceCommand("/api/voice/stop-speaking"));
  $("mute").addEventListener("click", () => voiceCommand("/api/voice/mute", { muted: !(app.voice && app.voice.muted) }));
  $("ask-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const input = $("ask-input");
    const text = input.value.trim();
    if (!text) return;
    input.value = "";
    scrollTranscript(true);
    await imageSettled();                 // an image still on its way goes with this question
    await voiceCommand("/api/voice/ask", { text });
    input.focus();
  });
  $("now-line").addEventListener("click", () => { location.hash = "#marvin/robot"; });
  $("open-last-inspector").addEventListener("click", () => { if (lastReply) openInspector(lastReply); });
  on("voice", renderVoice);
  setupAttach();
  on("transcript", ({ entry, fresh }) => addTranscript(entry, { fresh }));
  on("level", onLevel);
  on("utterance", onUtterance);
  on("partial", onPartial);
  on("say", onSay);
  on("state", renderNow);
  on("theme", () => { live.colors = null; });
  on("resize", fitNow);
  on("view", ({ view }) => { if (view === "talk") { scrollTranscript(true, false); fitNow(); } });
  on("reconnected", () => load().catch(() => {}));
  document.addEventListener("visibilitychange", ensureLoop);
}

export async function load() {
  const r = await api("/api/voice");
  app.voiceSettings = r.settings;
  for (const e of r.transcript) {
    try { addTranscript(e); } catch (err) { console.error("transcript entry not shown", e.id, err); }  // one bad entry never hides the rest
  }
  emit("voice", r.voice);
  scrollTranscript(true, false);
  if (!r.voice) toast("The voice is not reachable.");
}

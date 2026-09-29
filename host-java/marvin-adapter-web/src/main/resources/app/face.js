// SPDX-License-Identifier: MIT
// Marvin's eyes, drawn in the browser with the robot's own geometry: the expressions are face.py's table (the
// one FaceParams copies and the firmware draws), the lids and blinks are its render(), the motion its
// critically damped spring. One renderer serves every face in the app (Home, Talk, the wordmark), in the
// theme's colours: the eyes in --eye (warm ones lean to --eye-warm), the lids cut out so the orb shows
// through. Driven by the robot's expression (from /api/state) and, while the voice runs, by the voice
// (listening, thinking, speaking). With reduced motion, only the expression changes.

import { reduceMotion, cssVar, on } from "./core.js";

const KEYS = ["w", "h", "r", "sp", "dy", "open", "lt", "tilt", "lb", "bri", "warm"];
const P = (w, h, r, sp, dy, open, lt, tilt, lb, bri, warm) => ({ w, h, r, sp, dy, open, lt, tilt, lb, bri, warm });

// the robot's expressions (face.py EXPRESSIONS, FaceParams.EXPRESSIONS)
const ROBOT = {
  neutral: P(64, 82, 22, 104, 0, 1, 0, 0, 0, 1, 0.1),
  awake: P(66, 88, 24, 106, 0, 1, 0, 0, 0, 1, 0.1),
  surprised: P(72, 94, 32, 110, -4, 1, 0, 0, 0, 1, 0.1),
  attentive: P(60, 88, 20, 104, 0, 1, 0, 0, 0, 1, 0.1),
  content: P(68, 76, 26, 106, 0, 1, 0, 0, 0.36, 1, 0.2),
  calm: P(68, 68, 22, 104, 2, 1, 0, 0, 0, 1, 0.15),
  concerned: P(64, 80, 16, 104, 0, 1, 0.4, 0.4, 0, 1, 0.2),
  sleepy: P(66, 74, 22, 104, 4, 1, 0.55, 0, 0, 0.9, 0.45),
  asleep: P(60, 74, 22, 104, 14, 0, 0, 0, 0, 0.7, 0.9),
};
// the voice's expressions (the sidecar's conversation face)
const VOICE = {
  calm: P(68, 72, 22, 104, 2, 1, 0, 0, 0, 1, 0.15),
  attentive: P(68, 90, 26, 108, -3, 1, 0, 0, 0, 1, 0.1),
  thinking: P(64, 74, 20, 104, 0, 1, 0.14, 0.12, 0, 1, 0.12),
  speaking: P(68, 80, 24, 106, 0, 1, 0, 0, 0, 1, 0.15),
  muted: P(66, 70, 22, 104, 4, 1, 0.34, 0, 0, 0.8, 0.3),
  waking: P(66, 84, 24, 106, 0, 1, 0, 0, 0, 1, 0.1),
  concerned: ROBOT.concerned,
};
const BLINK = [0.07, 0.04, 0.13], SLOW = [0.35, 0.3, 0.55];
const vec = (p) => KEYS.map((k) => p[k]);
const obj = (a) => Object.fromEntries(KEYS.map((k, i) => [k, a[i]]));

/** What every face shows: the robot's expression and the voice's state. */
const shared = { robot: "neutral", voice: "off", muted: false, level: () => 0 };
const faces = new Set();

function parseColor(c) {
  const m = /^#([0-9a-f]{6})/i.exec(c);
  if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16));
  const r = /rgba?\(([^)]+)\)/.exec(c);
  return r ? r[1].split(",").slice(0, 3).map((x) => parseFloat(x)) : [128, 128, 128];
}

let colors = null;
function themeColors() {
  if (!colors) colors = { eye: parseColor(cssVar("--eye")), warm: parseColor(cssVar("--eye-warm")) };
  return colors;
}
on("theme", () => { colors = null; faces.forEach((f) => f.wake(true)); });

/** The expression and behaviour the faces follow now. */
function mode() {
  const v = shared.voice;
  if (v === "error") return { expr: VOICE.concerned, name: "concerned", act: "idle" };
  if (v === "starting") return { expr: VOICE.waking, name: "waking", act: "idle" };
  if (v === "listening") return { expr: VOICE.attentive, name: "attentive", act: "listening" };
  if (v === "thinking") return { expr: VOICE.thinking, name: "thinking", act: "thinking" };
  if (v === "speaking") return { expr: VOICE.speaking, name: "speaking", act: "speaking" };
  if (v === "idle" && shared.muted) return { expr: VOICE.muted, name: "muted", act: "rest" };
  const name = ROBOT[shared.robot] ? shared.robot : "neutral";
  const rest = name === "sleepy" || name === "asleep";
  if (v === "idle" && !rest) return { expr: VOICE.calm, name: "calm", act: "idle" };
  return { expr: ROBOT[name], name, act: rest ? "rest" : "idle" };
}

const LABELS = {
  concerned: "Marvin looks concerned", waking: "Marvin is waking up", attentive: "Marvin is listening",
  thinking: "Marvin is thinking", speaking: "Marvin is speaking", muted: "Marvin's microphone is muted",
  calm: "Marvin is calm, waiting", neutral: "Marvin is here", awake: "Marvin is awake", surprised: "Marvin looks surprised",
  content: "Marvin looks content", sleepy: "Marvin is sleepy", asleep: "Marvin is asleep",
};

/** Updates what the faces show. `robot`: the robot face's expression name; `voice`: off, starting, idle,
 *  listening, thinking, speaking, error. */
export function setFaceState({ robot, voice, muted } = {}) {
  let changed = false;
  if (robot != null && robot !== shared.robot) { shared.robot = robot; changed = true; }
  if (voice != null && voice !== shared.voice) { shared.voice = voice; changed = true; }
  if (muted != null && muted !== shared.muted) { shared.muted = muted; changed = true; }
  if (changed) faces.forEach((f) => f.retarget());
}

/** Where the faces read the loudness they move with (the microphone, or Marvin's voice). */
export function setLevelSource(fn) { shared.level = fn; }

export function faceLabel() { return LABELS[mode().name] || "Marvin"; }

function spring(x, v, tgt, w, dt) {
  const e = Math.exp(-w * dt);
  for (let i = 0; i < x.length; i++) {
    const x0 = x[i] - tgt[i], c = v[i] + w * x0;
    x[i] = tgt[i] + (x0 + c * dt) * e;
    v[i] = (v[i] - w * c * dt) * e;
  }
}
const smooth = (u) => { u = Math.min(1, Math.max(0, u)); return u * u * (3 - 2 * u); };
function closure(t, s0, [c, h, o]) {
  const u = t - s0;
  if (u < 0 || u > c + h + o) return 0;
  if (u < c) return smooth(u / c);
  if (u < c + h) return 1;
  return 1 - smooth((u - c - h) / o);
}

function rrect(g, cx, cy, w, h, r) {
  r = Math.max(0, Math.min(r, w / 2, h / 2));
  const x = cx - w / 2, y = cy - h / 2;
  g.beginPath();
  g.moveTo(x + r, y);
  g.arcTo(x + w, y, x + w, y + h, r);
  g.arcTo(x + w, y + h, x, y + h, r);
  g.arcTo(x, y + h, x, y, r);
  g.arcTo(x, y, x + w, y, r);
  g.closePath();
  g.fill();
}

/**
 * A face on a canvas. `span`: how many of the robot screen's pixels (240 x 280) the canvas's width shows;
 * 196 fills it with the eyes, larger leaves room around them. `still`: drawn once per change (the wordmark).
 */
export class Face {
  constructor(canvas, { span = 196, still = false, labelled = null } = {}) {
    this.cv = canvas;
    this.span = span;
    this.still = still;
    this.labelled = labelled;
    const m = mode();
    this.target = m.expr;
    this.p = vec(m.expr);
    this.pv = this.p.map(() => 0);
    this.gaze = [0, 0]; this.gv = [0, 0];
    this.last = 0; this.raf = 0; this.idleTimer = 0;
    this.blinks = []; this.nextBlink = 0; this.glance = [0, 0]; this.nextGlance = 0; this.thinkSide = 1; this.nextThink = 0;
    faces.add(this);
    document.addEventListener("visibilitychange", () => this.wake());
    reduceMotion.addEventListener("change", () => this.wake(true));
    if (window.ResizeObserver) new ResizeObserver(() => this.wake(true)).observe(canvas);
    this.retarget();
  }

  retarget() {
    const m = mode();
    this.target = m.expr;
    this.act = m.act;
    if (this.labelled) this.labelled.setAttribute("aria-label", LABELS[m.name] || "Marvin");
    if (this.still || reduceMotion.matches) {
      this.p = vec(m.expr);
      this.gaze = [0, m.act === "thinking" ? -10 : 0];
    }
    this.wake(true);
  }

  color(q) {
    const c = themeColors();
    const w = Math.min(1, Math.max(0, q.warm)), b = Math.min(1, Math.max(0.35, q.bri));
    const rgb = c.eye.map((e, i) => Math.round((1 - w * 0.6) * e + w * 0.6 * c.warm[i]));
    return `rgba(${rgb.join(",")},${b.toFixed(3)})`;
  }

  draw(q, gx, gy, blink) {
    const cv = this.cv;
    const w = cv.clientWidth, h = cv.clientHeight;
    if (!w || !h) return;
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) {
      cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr);
    }
    const g = cv.getContext("2d");
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, cv.width, cv.height);
    const sc = (w / this.span) * dpr;
    g.setTransform(sc, 0, 0, sc, (w * dpr) / 2 - 120 * sc, (h * dpr) / 2 - 146 * sc);
    const lean = Math.max(-1, Math.min(1, gx / 28));
    const openness = Math.max(0, Math.min(1, q.open * (1 - blink)));
    const eye = this.color(q);
    for (const side of [-1, 1]) {
      const k = 1 + side * 0.06 * lean;
      const ew = q.w * k, eh = q.h * k;
      const cx = 120 + (side * q.sp) / 2 + gx;
      const he = Math.max(5, eh * openness);
      const cy = 146 + q.dy + gy + (eh - he) * 0.35;
      const top = cy - he / 2, bottom = cy + he / 2;
      g.globalCompositeOperation = "source-over";
      g.fillStyle = eye;
      rrect(g, cx, cy, ew, he, q.r * k);
      // the lids, cut out of the eye (face.py draws them in the screen's background colour)
      g.globalCompositeOperation = "destination-out";
      g.fillStyle = "#000";
      if (q.lt > 1e-3 && he > 5) {
        const ymid = top + q.lt * he, slope = (q.tilt * he) / 2;
        const xin = cx - side * (ew / 2 + 3), xout = cx + side * (ew / 2 + 3), ycap = top - 3;
        g.beginPath();
        g.moveTo(xin, ycap); g.lineTo(xout, ycap);
        g.lineTo(xout, Math.max(ymid + slope, ycap + 1)); g.lineTo(xin, Math.max(ymid - slope, ycap + 1));
        g.closePath(); g.fill();
      }
      if (q.lb > 1e-3 && he > 5) {
        const rx = ew * 0.56, ry = he * 0.5;
        g.beginPath(); g.ellipse(cx, bottom - q.lb * he + ry, rx, ry, 0, 0, 2 * Math.PI); g.fill();
      }
    }
    g.globalCompositeOperation = "source-over";
  }

  behaviour(t, lvl) {
    const q = obj(this.p);
    let gt = [0, 0], w = 16;
    if (this.act === "listening") {                  // looking at you, a little wider with your voice
      q.w *= 1 + 0.07 * lvl; q.h *= 1 + 0.1 * lvl;
      if (t > this.nextGlance) { this.glance = [(Math.random() - 0.5) * 4, (Math.random() - 0.5) * 2.5]; this.nextGlance = t + 0.8 + Math.random() * 1.6; }
      gt = [this.glance[0], -3 + this.glance[1]];
    } else if (this.act === "thinking") {            // glancing up and aside
      if (t > this.nextThink) {
        this.thinkSide = -this.thinkSide; this.nextThink = t + 1.1 + Math.random() * 1.1;
        if (Math.random() < 0.35) this.blinks.push([t + 0.2, SLOW]);
      }
      gt = [this.thinkSide * 15, -13]; w = 11;
    } else if (this.act === "speaking") {            // moving with the voice
      q.h *= 1 - 0.16 * lvl; q.w *= 1 + 0.05 * lvl; q.dy -= 4 * lvl;
      gt = [0, -1];
    } else if (this.act === "idle") {                // small glances now and then
      if (t > this.nextGlance) {
        this.glance = Math.random() < 0.3 ? [(Math.random() - 0.5) * 30, (Math.random() - 0.6) * 10] : [(Math.random() - 0.5) * 5, (Math.random() - 0.5) * 3];
        this.nextGlance = t + 1.2 + Math.random() * 2.5;
      }
      gt = this.glance;
    } else {
      gt = [0, 5]; w = 4;
    }
    spring(this.gaze, this.gv, gt, w, Math.min(0.05, t - this.last || 0));
    const rest = this.act === "rest";
    if (!this.nextBlink) this.nextBlink = t + 2 + Math.random() * 4;
    if (t >= this.nextBlink && this.target.open > 0) {
      this.blinks.push([t, rest ? SLOW : BLINK]);
      if (!rest && Math.random() < 0.15) this.blinks.push([t + 0.32, BLINK]);
      this.nextBlink = t + (rest ? 5 + Math.random() * 5 : 2 + Math.random() * 4);
    }
    this.blinks = this.blinks.filter(([s0, tm]) => t <= s0 + tm[0] + tm[1] + tm[2]);
    const blink = Math.max(0, ...this.blinks.map(([s0, tm]) => closure(t, s0, tm)));
    return [q, blink];
  }

  visible() { return !document.hidden && this.cv.getClientRects().length > 0; }

  tick(ts) {
    this.raf = 0;
    if (!this.visible()) { this.idleTimer = setTimeout(() => this.wake(), 600); return; }
    const t = ts / 1000;
    const dt = this.last ? Math.min(0.05, t - this.last) : 0;
    if (this.still || reduceMotion.matches) {
      this.draw(obj(this.p), this.gaze[0], this.gaze[1], 0);
      this.last = 0;
      return;
    }
    const omega = this.act === "rest" ? 2.2 : this.act === "speaking" ? 12 : 8;
    spring(this.p, this.pv, vec(this.target), omega, dt);
    const lvl = this.act === "listening" || this.act === "speaking" ? shared.level() : 0;
    const [q, blink] = this.behaviour(t, lvl);
    this.last = t;
    this.draw(q, this.gaze[0], this.gaze[1], blink);
    // at rest and settled: a few frames a second are enough
    const settled = this.pv.every((x) => Math.abs(x) < 0.01) && Math.abs(this.gv[0]) + Math.abs(this.gv[1]) < 0.01 && !this.blinks.length;
    if (settled && this.act === "rest") this.idleTimer = setTimeout(() => this.wake(), 150);
    else this.raf = requestAnimationFrame((x) => this.tick(x));
  }

  wake(now = false) {
    clearTimeout(this.idleTimer);
    if (now && this.raf) { cancelAnimationFrame(this.raf); this.raf = 0; }
    if (!this.raf) this.raf = requestAnimationFrame((x) => this.tick(x));
  }
}

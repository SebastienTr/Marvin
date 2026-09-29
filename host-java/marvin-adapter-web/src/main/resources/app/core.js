// SPDX-License-Identifier: MIT
// Shared helpers of the app: DOM shortcuts, the API, formatting, a small event bus and the app's state.
// Plain ES modules, no build step, nothing loaded from anywhere but this host.

export const $ = (id) => document.getElementById(id);
export const SVG = "http://www.w3.org/2000/svg";
export const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
export const phone = window.matchMedia("(max-width: 700px)");

/** What the app knows, as the server last said it. */
export const app = {
  view: "home",
  settings: { clock: "24h", break_interval_min: 50 },
  state: null,          // /api/state "state": presence, break, status sentence, simulated
  today: null,          // today's stats
  voice: null,          // voice snapshot
  voiceSettings: null,
  connection: "connecting",   // connecting | live | lost | offline (robot)
  memory: null,         // /api/memory overview
};

const bus = new EventTarget();
/** Listen to an app event ("state", "today", "voice", "transcript", "memory", "theme", "view", ...). */
export function on(name, fn) {
  bus.addEventListener(name, (ev) => fn(ev.detail));
}
export function emit(name, detail) {
  bus.dispatchEvent(new CustomEvent(name, { detail }));
}

export function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

export async function api(path, options) {
  const r = await fetch(path, { credentials: "same-origin", cache: "no-store", ...options });
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(body.error || `HTTP ${r.status}`);
  return body;
}

export const post = (path, body = {}) => api(path, {
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
});

export function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

export const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
export const fmtSeconds = (x) => `${x < 10 ? x.toFixed(2) : x.toFixed(1)} s`;

export function fmtDuration(s, { short = false } = {}) {
  s = Math.max(0, s || 0);
  const m = Math.floor(s / 60);
  if (m < 60) return short ? `${m}m` : `${m} min`;
  const h = Math.floor(m / 60), r = m % 60;
  if (short) return r ? `${h}h${String(r).padStart(2, "0")}` : `${h}h`;
  return r ? `${h} h ${String(r).padStart(2, "0")}` : `${h} h`;
}

/** "1h 42" / "38 min", the units smaller (as DOM nodes). */
export function durationNodes(s) {
  const m = Math.floor(Math.max(0, s || 0) / 60);
  if (m < 60) return [String(m), el("small", null, " min")];
  const h = Math.floor(m / 60), r = m % 60;
  return r ? [String(h), el("small", null, "h "), String(r).padStart(2, "0")] : [String(h), el("small", null, " h")];
}

export function fmtTime(ts) {
  const d = new Date(ts * 1000);
  const h = d.getHours(), m = String(d.getMinutes()).padStart(2, "0");
  if (app.settings.clock === "12h") return `${((h + 11) % 12) + 1}:${m} ${h < 12 ? "am" : "pm"}`;
  return `${String(h).padStart(2, "0")}:${m}`;
}

export function fmtHour(h) {
  if (app.settings.clock === "12h") return `${((h + 11) % 12) + 1}${h % 24 < 12 ? "am" : "pm"}`;
  return `${String(h % 24).padStart(2, "0")}:00`;
}

export const isoDate = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
export const parseIso = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };
export const longDate = (iso) => parseIso(iso).toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" });

export function dayLabel(iso) {
  const d = parseIso(iso), today = parseIso(app.today ? app.today.date : isoDate(new Date()));
  const diff = Math.round((today - d) / 86400000);
  if (diff === 0) return "Today";
  if (diff === 1) return "Yesterday";
  return longDate(iso);
}

/** "Today, 11:16", "Yesterday, 09:32", "24 Sep, 14:08". */
export function whenLabel(ts) {
  const d = new Date(ts * 1000);
  const day = dayLabel(isoDate(d));
  const date = day === "Today" || day === "Yesterday" ? day : d.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
  return `${date}, ${fmtTime(ts)}`;
}

/** A time element for `ts` (Unix seconds). */
export function timeEl(ts, text) {
  const t = el("time", null, text != null ? text : fmtTime(ts));
  t.dateTime = new Date(ts * 1000).toISOString();
  return t;
}

/** A polite, short message at the bottom of the screen. */
export function toast(text) {
  const t = $("toast");
  t.textContent = text;
  clearTimeout(t._timer);
  t._timer = setTimeout(() => { t.textContent = ""; }, 3800);
}

/** Reads and writes this browser's preferences; storage may be missing (private windows). */
export const local = {
  get(key, fallback) {
    try {
      const v = window.localStorage.getItem(key);
      return v == null ? fallback : JSON.parse(v);
    } catch (e) {
      return fallback;
    }
  },
  set(key, value) {
    try {
      if (value == null) window.localStorage.removeItem(key);
      else window.localStorage.setItem(key, JSON.stringify(value));
    } catch (e) { /* not kept: the page still works */ }
  },
};

/** A canvas sized to its box at the device's pixel ratio; null while not laid out. */
export function canvas2d(c) {
  const r = c.getBoundingClientRect();
  if (!r.width) return null;
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = Math.round(r.width * dpr), h = Math.round(r.height * dpr);
  if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
  const ctx = c.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, r.width, r.height);
  return { ctx, w: r.width, h: r.height };
}

/** Opens the shared dialog with a title and content; returns the dialog's body. */
export function openDialog(title, { label = "Marvin", wide = false } = {}) {
  const d = $("dialog");
  $("dialog-label").textContent = label;
  $("dialog-title").textContent = title;
  const body = $("dialog-body");
  body.replaceChildren();
  d.classList.toggle("wide", wide);
  if (!d.open) d.showModal();
  d.scrollTop = 0;
  return body;
}

export function closeDialog() {
  const d = $("dialog");
  if (d.open) d.close();
}

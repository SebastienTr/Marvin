// SPDX-License-Identifier: MIT
// The sensor views both Marvin > Robot and Senses draw: the lidar's top view (a scale that follows the scan, or a
// range chosen by hand), the LD2450's field of view, the vital signs' sparklines and waves, and a device with its
// link. Canvases in the appearance's colours; nothing here fetches anything.

import { el, cssVar, canvas2d } from "./core.js";

export function palette() {
  return {
    text: cssVar("--ink"), muted: cssVar("--muted"), seated: cssVar("--seated"), accent: cssVar("--reminder"),
    line: cssVar("--line"), fov: cssVar("--soft"), point: cssVar("--live"), strong: cssVar("--strong"), bg: cssVar("--surface"),
  };
}

// ------------------------------------------------------------------ lidar

/** The ranges the lidar view offers, in metres. */
export const LIDAR_RANGES = [0.5, 1, 2, 4, 8];

/**
 * What a scan says: how many bearings returned something, the nearest return (cm, and its bearing in degrees clockwise
 * from the front), the 85th percentile of the returns (what the automatic scale fits), its age and points; null before
 * any scan.
 */
export function lidarStats(scene) {
  const l = scene && scene.lidar;
  if (!l || !Array.isArray(l.ranges_cm)) return null;
  const r = l.ranges_cm;
  const nz = [];
  let near = 0, at = -1;
  for (let i = 0; i < r.length; i++) {
    const d = r[i];
    if (!(d > 0)) continue;
    nz.push(d);
    if (!near || d < near) { near = d; at = i; }
  }
  nz.sort((a, b) => a - b);
  const p85 = nz.length ? nz[Math.min(nz.length - 1, Math.floor(nz.length * 0.85))] : 0;
  return {
    bearings: nz.length, nearest_cm: near || null, nearest_deg: at >= 0 ? Math.round(((at + 0.5) * 360) / r.length) % 360 : null,
    p85, age_s: l.age_s, points: l.points,
  };
}

/** The smallest range that holds most of the scan: a lidar on a cluttered desk is shown at 0.5 or 1 m, a room at 4. */
export function autoRange(stats) {
  if (!stats || !stats.bearings) return 2;
  const need = (stats.p85 * 1.15) / 100;
  return LIDAR_RANGES.find((m) => m >= need) || LIDAR_RANGES[LIDAR_RANGES.length - 1];
}

function ringStep(meters) {
  return meters <= 0.5 ? 0.1 : meters <= 1 ? 0.25 : meters <= 2 ? 0.5 : meters <= 4 ? 1 : 2;
}

export const fmtDistance = (m) => (m < 1 ? `${Math.round(m * 100)} cm` : `${+m.toFixed(2)} m`);

/**
 * The lidar's top view, the robot in the middle facing up. `range`: "auto" or metres; `radar`: an LD2450 is connected
 * (its field of view is shaded, its people and their path drawn). Returns { range, stats } (range in metres).
 */
export function drawLidar(canvas, scene, { range = "auto", radar = false } = {}) {
  const g = canvas2d(canvas);
  const stats = lidarStats(scene);
  const meters = range === "auto" ? autoRange(stats) : Number(range);
  if (!g) return { range: meters, stats };
  const { ctx, w, h } = g, col = palette();
  const cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2 - 10;
  const scale = R / (meters * 100);                     // pixels per centimetre
  const lidar = scene && scene.lidar;
  if (radar) {
    const fov = ((scene && scene.radar) || { half_angle_deg: 60 }).half_angle_deg * Math.PI / 180;
    ctx.fillStyle = col.fov;
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, R, -Math.PI / 2 - fov, -Math.PI / 2 + fov); ctx.closePath(); ctx.fill();
  }
  // rings at a step that suits the range, labelled along the lower right diagonal
  const step = ringStep(meters);
  const n = Math.round(meters / step);
  ctx.lineWidth = 1;
  ctx.strokeStyle = col.line;
  ctx.fillStyle = col.muted;
  ctx.font = "11px system-ui, sans-serif";
  ctx.textAlign = "left";
  for (let k = 1; k <= n; k++) {
    const rr = k * step * 100 * scale;
    ctx.beginPath(); ctx.arc(cx, cy, rr, 0, 2 * Math.PI); ctx.stroke();
    if (n <= 5 || k % 2 === 0 || k === n) ctx.fillText(fmtDistance(k * step), cx + rr * 0.7071 + 4, cy + rr * 0.7071 + 4);
  }
  ctx.globalAlpha = 0.5;
  ctx.beginPath(); ctx.moveTo(cx, cy - R); ctx.lineTo(cx, cy + R); ctx.moveTo(cx - R, cy); ctx.lineTo(cx + R, cy); ctx.stroke();
  ctx.globalAlpha = 1;
  // the robot first: the returns are drawn over it, never hidden under it
  const s = Math.max(4, Math.min(8, R * 0.03));
  ctx.fillStyle = col.strong;
  ctx.beginPath(); ctx.moveTo(cx, cy - s * 1.4); ctx.lineTo(cx + s, cy + s); ctx.lineTo(cx - s, cy + s); ctx.closePath(); ctx.fill();
  if (lidar && Array.isArray(lidar.ranges_cm)) {
    const ranges = lidar.ranges_cm;
    const ps = Math.max(1.8, Math.min(3.4, R / 95));    // a dot a few pixels wide whatever the canvas
    const stale = lidar.age_s > 2;
    ctx.fillStyle = col.point;
    for (let i = 0; i < ranges.length; i++) {
      const d = ranges[i];
      if (!(d > 0)) continue;
      const a = ((i + 0.5) * 360 / ranges.length) * Math.PI / 180;
      const beyond = d * scale > R;
      const rr = beyond ? R + 3 : d * scale;            // beyond the range: a tick on the edge
      ctx.globalAlpha = (stale ? 0.4 : 0.95) * (beyond ? 0.45 : 1);
      ctx.fillRect(cx + Math.sin(a) * rr - ps / 2, cy - Math.cos(a) * rr - ps / 2, ps, ps);
    }
    ctx.globalAlpha = 1;
    // the nearest return, marked and measured
    if (stats && stats.nearest_cm && stats.nearest_cm * scale <= R) {
      const a = stats.nearest_deg * Math.PI / 180;
      const x = cx + Math.sin(a) * stats.nearest_cm * scale, y = cy - Math.cos(a) * stats.nearest_cm * scale;
      ctx.strokeStyle = col.accent;
      ctx.setLineDash([3, 3]);
      ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(x, y); ctx.stroke();
      ctx.setLineDash([]);
      ctx.lineWidth = 1.6;
      ctx.beginPath(); ctx.arc(x, y, ps + 3, 0, 2 * Math.PI); ctx.stroke();
      ctx.lineWidth = 1;
    }
  }
  if (radar && scene) {
    const toXY = (r, f) => [cx + r * scale, cy - f * scale];
    drawTrail(ctx, scene.trail || [], toXY, col);
    for (const [r, f] of scene.targets || []) drawPerson(ctx, ...toXY(r, f), col);
  }
  return { range: meters, stats };
}

// ------------------------------------------------------------------ radar

/**
 * Where the radar puts someone, in plain words: the distance from the radar and the way, from the radar's own x
 * (sideways, positive to the right of someone facing it) and y (out of its face), in cm. For a radar mounted flat
 * ("flat") its y points up; turned 90° ("side"), its x points up. Returns { dist, dir, angle } (angle in degrees,
 * positive to the right, or up when turned).
 */
export function describeTarget(x, y, mount = "upright") {
  const d = Math.hypot(x, y) / 100;
  const a = Math.round(Math.atan2(x, y) * 180 / Math.PI);
  const dist = `${d.toFixed(2)} m`;
  if (Math.abs(a) < 3) return { dist, dir: mount === "flat" ? "straight up" : "straight ahead", angle: a };
  const side = mount === "side" ? (a > 0 ? "up" : "down") : a > 0 ? "right" : "left";
  return { dist, dir: mount === "flat" ? `${Math.abs(a)}° ${side} of up` : `${Math.abs(a)}° ${side}`, angle: a };
}

function drawPerson(ctx, x, y, col) {
  ctx.globalAlpha = 0.22;
  ctx.fillStyle = col.accent;
  ctx.beginPath(); ctx.arc(x, y, 12, 0, 2 * Math.PI); ctx.fill();
  ctx.globalAlpha = 1;
  ctx.beginPath(); ctx.arc(x, y, 5.5, 0, 2 * Math.PI); ctx.fill();
}

function drawTrail(ctx, trail, toXY, col) {
  ctx.fillStyle = col.accent;
  for (const [age, r, f] of trail) {
    const [x, y] = toXY(r, f);
    ctx.globalAlpha = Math.max(0.06, 0.45 * (1 - age / 5));
    ctx.beginPath(); ctx.arc(x, y, 2.2, 0, 2 * Math.PI); ctx.fill();
  }
  ctx.globalAlpha = 1;
}

/** The LD2450's field of view with the people it follows and their last five seconds. Returns { meters, nearest_m }. */
export function drawRadar(canvas, scene) {
  const targets = (scene && scene.targets) || [];
  const trail = (scene && scene.trail) || [];
  const nearest = targets.length ? Math.min(...targets.map(([r, f]) => Math.hypot(r, f))) / 100 : null;
  const g = canvas2d(canvas);
  if (!g) return { nearest_m: nearest };
  const { ctx, w, h } = g, col = palette();
  const fovDeg = ((scene && scene.radar) || { half_angle_deg: 60 }).half_angle_deg;
  const fov = fovDeg * Math.PI / 180;
  const far = Math.max(0, ...targets.map(([r, f]) => Math.hypot(r, f)), ...trail.map(([, r, f]) => Math.hypot(r, f)));
  const meters = Math.max(3, Math.min(6, Math.ceil(far / 100 + 0.5)));
  const cx = w / 2, cy = h - 14;
  const scale = Math.min((h - 30) / (meters * 100), (w / 2 - 12) / (Math.sin(fov) * meters * 100));
  const a0 = -Math.PI / 2 - fov, a1 = -Math.PI / 2 + fov;
  ctx.fillStyle = col.fov;
  ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, meters * 100 * scale, a0, a1); ctx.closePath(); ctx.fill();
  ctx.lineWidth = 1;
  ctx.strokeStyle = col.line;
  for (let k = 1; k <= meters; k++) { ctx.beginPath(); ctx.arc(cx, cy, k * 100 * scale, a0, a1); ctx.stroke(); }
  for (const deg of [-fovDeg, -fovDeg / 2, 0, fovDeg / 2, fovDeg]) {
    const a = -Math.PI / 2 + deg * Math.PI / 180;
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + Math.cos(a) * meters * 100 * scale, cy + Math.sin(a) * meters * 100 * scale); ctx.stroke();
  }
  ctx.fillStyle = col.muted;
  ctx.font = "11px system-ui, sans-serif";
  ctx.textAlign = "left";
  for (let k = 1; k <= meters; k++) {
    const rr = k * 100 * scale;
    ctx.fillText(`${k} m`, Math.min(w - 26, cx + Math.sin(fov) * rr + 6), cy - Math.cos(fov) * rr + 4);
  }
  const toXY = (r, f) => [cx + r * scale, cy - f * scale];
  drawTrail(ctx, trail, toXY, col);
  for (const [r, f] of targets) drawPerson(ctx, ...toXY(r, f), col);
  ctx.fillStyle = col.text;
  ctx.fillRect(cx - 9, cy + 2, 18, 4);
  return { meters, nearest_m: nearest };
}

// ------------------------------------------------------------------ vital signs

export function drawSpark(c, series, lo, hi) {
  const g = canvas2d(c);
  if (!g) return;
  const { ctx, w, h } = g, col = palette();
  ctx.strokeStyle = col.line;
  ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(0, h - 0.5); ctx.lineTo(w, h - 0.5); ctx.stroke();
  const vals = series.filter((p) => p[1] != null).map((p) => p[1]);
  if (!vals.length) return;
  let a = Math.min(...vals), b = Math.max(...vals);
  const mid = (a + b) / 2, span = Math.max(b - a, hi - lo);
  a = mid - span / 2; b = mid + span / 2;
  const x = (age) => w - (age / 300) * w;
  const y = (v) => 6 + (1 - (v - a) / (b - a)) * (h - 14);
  ctx.strokeStyle = col.seated;
  ctx.lineWidth = 1.8;
  ctx.lineJoin = "round";
  ctx.beginPath();
  let pen = false, last = null;
  for (const [age, v] of series.slice().sort((p, q) => q[0] - p[0])) {
    if (v == null) { pen = false; continue; }
    if (pen) ctx.lineTo(x(age), y(v)); else ctx.moveTo(x(age), y(v));
    pen = true; last = [age, v];
  }
  ctx.stroke();
  if (last) { ctx.fillStyle = col.text; ctx.beginPath(); ctx.arc(x(last[0]), y(last[1]), 2.6, 0, 2 * Math.PI); ctx.fill(); }
  ctx.fillStyle = col.muted;
  ctx.font = "10px system-ui, sans-serif";
  ctx.textAlign = "left";
  ctx.fillText(`${Math.round(Math.max(...vals))}`, 2, 10);
  ctx.fillText(`${Math.round(Math.min(...vals))}`, 2, h - 4);
}

export function drawWave(c, waves, k) {
  const g = canvas2d(c);
  if (!g) return;
  const { ctx, w, h } = g, col = palette();
  const mid = h / 2;
  ctx.strokeStyle = waves.length ? col.muted : col.line;
  ctx.lineWidth = 1.3;
  ctx.beginPath();
  if (!waves.length) { ctx.moveTo(0, mid); ctx.lineTo(w, mid); ctx.stroke(); return; }
  waves.slice().sort((p, q) => q[0] - p[0]).forEach((p, i) => {
    const x = w - (p[0] / 15) * w, y = mid - p[k] * (h / 2 - 3);
    if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y);
  });
  ctx.stroke();
}

// ------------------------------------------------------------------ devices

export function ago(s) {
  if (s < 1.5) return "live";
  if (s < 90) return `${Math.round(s)} s ago`;
  return `${Math.round(s / 60)} min ago`;
}

function uptime(s) {
  if (s == null) return "";
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return h ? `up ${h} h ${String(m).padStart(2, "0")}` : `up ${m} min`;
}

export function metric(label, value, unit, warn) {
  const d = el("div", warn ? "warn" : "");
  const dd = el("dd", null, value);
  if (unit) dd.append(el("small", null, unit));
  d.append(el("dt", null, label), dd);
  return d;
}

export const fmtRate = (x) => (x == null ? "–" : x >= 10 ? String(Math.round(x)) : x.toFixed(1));

/** A device as a list item: its name, link, board and firmware, tags, and its link's counters. */
export function deviceItem(d) {
  const li = el("li", "device");
  li.dataset.role = d.role;
  const head = el("div", "device-head");
  head.append(el("span", "device-name", d.label), el("span", "small muted", d.name));
  const link = el("span", `link ${!d.online ? "down" : d.age_s >= 3 ? "stale" : ""}`);
  link.append(el("span", "dot"), el("span", null, d.online ? ago(d.age_s) : `offline · ${ago(d.age_s)}`));
  if (d.rssi != null) {
    const bars = el("span", "bars");
    bars.setAttribute("role", "img");
    bars.setAttribute("aria-label", `Wi-Fi ${d.rssi} dBm, ${d.rssi_bars} of 4 bars`);
    for (let i = 1; i <= 4; i++) bars.append(el("i", i <= d.rssi_bars ? "on" : ""));
    link.append(bars, el("span", null, `${d.rssi} dBm`));
  }
  head.append(link);
  li.append(head);
  const usb = d.role === "usb-sensors";         // a lidar or radar on a USB adapter, read by this computer
  const sub = [d.board, d.firmware && `firmware ${d.firmware}`, usb ? "on this computer" : d.ip, uptime(d.uptime_s)].filter(Boolean);
  li.append(el("p", "device-sub", sub.join(" · ")));
  const tags = el("div", "tags");
  if (d.simulated) tags.append(el("span", "tag", "Simulated data"));
  if (usb) tags.append(el("span", "tag", "Real sensors over USB"));
  if (d.camera) tags.append(el("span", "tag", "Camera"));
  if (d.audio) tags.append(el("span", "tag", "Speaker and mic"));
  if (tags.children.length) li.append(tags);
  const r = d.rates || {};
  const m = el("dl", "metrics");
  m.append(metric("Datagrams", fmtRate(r.datagrams), "/s"));
  m.append(metric("Loss", d.loss_pct == null ? "–" : d.loss_pct.toFixed(1), "%", d.loss_pct != null && d.loss_pct >= 2));
  m.append(metric("CRC errors", String(d.crc_errors), "", false));
  // the USB bridge shows the sensor it carries (a lidar until it says otherwise)
  if (usb ? r.scans || !r.radar : d.role !== "vitals" || r.scans) m.append(metric("Lidar", fmtRate(r.scans), `/s · ${d.points} pts`));
  if (usb ? r.radar : d.role !== "vitals" || r.radar) m.append(metric("Radar", fmtRate(r.radar), "/s"));
  if (d.role === "vitals" || r.vitals) m.append(metric("Vital signs", fmtRate(r.vitals), "/s"));
  if (r.audio) m.append(metric("Audio", fmtRate(r.audio), "/s"));
  if (d.shed) m.append(metric("Dropped by host", String(d.shed), "", true));
  li.append(m);
  return li;
}

/** Whether an LD2450 sends targets now (its field of view is worth drawing). */
export const radarLive = (devices) => (devices || []).some((d) => d.online && d.rates && d.rates.radar > 0);

// SPDX-License-Identifier: MIT
// Home's "Heart and breathing" (docs/ui.md): the MR60BHA2's heart rate and breathing now, whether the reading can be
// trusted (good, settling, none), how far away you sit, and the last 24 hours as two lanes, one point per minute, gaps
// where nothing was measured. From GET /api/vitals?hours=24 (contract C3) when Home opens and every minute while it is
// on screen, and the stream's "vitals" events (1 Hz) for the numbers now. Hidden while there is no vital-signs radar.
// A reading, never a diagnosis.

import { $, app, el, api, on, SVG, fmtHour } from "./core.js";

const HOURS = 24;
const REFRESH_MS = 60000;
const QUALITY = {
  good: ["Measured", ""],
  unstable: ["Settling", "Readings, not trusted yet: sit still for a moment."],
  none: ["Not readable now", "Sit still in front of Marvin, within a metre or so."],
};
// each lane's fixed scale, widened when a value falls outside it
const LANES = [
  { key: "hr", name: "Heart rate", unit: "bpm", lo: 40, hi: 120, cls: "vl-hr" },
  { key: "br", name: "Breathing", unit: "/min", lo: 6, hi: 30, cls: "vl-br" },
];

const v = { current: null, minutes: [], available: null, timer: 0, loading: null };

const visible = () => app.view === "home" && !document.hidden;

async function fetchHistory() {
  try {
    const r = await api(`/api/vitals?hours=${HOURS}`);
    v.available = true;
    v.minutes = Array.isArray(r.minutes) ? r.minutes : [];
    if (r.current !== undefined) v.current = r.current;
  } catch (e) {
    // no vitals history on this host (or it does not answer): the card stays hidden
    v.available = false;
  }
  render();
}

export function load() {
  if (!v.loading) v.loading = fetchHistory().finally(() => { v.loading = null; });
  return v.loading;
}

function schedule() {
  clearTimeout(v.timer);
  if (visible()) v.timer = setTimeout(() => { load().then(schedule); }, REFRESH_MS);
}

const measured = () => v.minutes.some((m) => m.n > 0 && (m.hr != null || m.br != null));

function render() {
  const card = $("vitals");
  const show = v.available && (v.current != null || measured());
  if (!show) { card.dataset.empty = ""; return; }
  delete card.dataset.empty;
  const c = v.current;
  const q = c ? c.quality || "none" : "none";
  const [word, hint] = QUALITY[q] || [q, ""];
  const pill = $("vitals-quality");
  pill.textContent = c && app.state && app.state.simulated ? `${word} · simulated` : word;
  pill.className = `pill${q === "good" ? "" : " attention-pill"}`;
  const num = (x, unit) => (x == null ? ["–"] : [String(Math.round(x)), el("small", null, ` ${unit}`)]);
  $("vitals-hr").replaceChildren(...num(c && q !== "none" ? c.hr : null, "bpm"));
  $("vitals-br").replaceChildren(...num(c && q !== "none" ? c.br : null, "/min"));
  $("vitals-distance").replaceChildren(...(c && c.distance_m != null ? [c.distance_m.toFixed(2), el("small", null, " m")] : ["–"]));
  card.classList.toggle("unsettled", q !== "good");
  const note = $("vitals-note");
  note.textContent = `${hint ? `${hint} ` : ""}Measured by the MR60BHA2 while you sit still in front of it, one point per minute over the last ${HOURS} hours. A reading, not a diagnosis.`;
  drawChart();
}

function drawChart() {
  const svg = $("vitals-chart");
  const r = svg.getBoundingClientRect();
  if (!r.width) return;
  const w = r.width, laneH = 64, gap = 18, h = LANES.length * laneH + (LANES.length - 1) * gap;
  svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
  svg.setAttribute("height", String(h));
  svg.replaceChildren();
  const now = v.current && v.current.t ? Math.max(v.current.t, Date.now() / 1000 - 5) : Date.now() / 1000;
  const t0 = now - HOURS * 3600;
  const x = (t) => ((t - t0) / (HOURS * 3600)) * w;
  const node = (tag, attrs) => {
    const n = document.createElementNS(SVG, tag);
    for (const [k, val] of Object.entries(attrs)) n.setAttribute(k, String(val));
    return n;
  };
  const desc = [];
  LANES.forEach((lane, i) => {
    const top = i * (laneH + gap);
    const pts = v.minutes.filter((m) => m.n > 0 && m[lane.key] != null && m.t >= t0);
    const vals = pts.map((m) => m[lane.key]);
    const lo = Math.min(lane.lo, ...vals), hi = Math.max(lane.hi, ...vals);
    const y = (val) => top + laneH - 4 - ((val - lo) / (hi - lo)) * (laneH - 8);
    svg.append(node("rect", { x: 0, y: top, width: w, height: laneH, rx: 6, class: "vl-track" }));
    for (let hh = 6; hh < HOURS; hh += 6) svg.append(node("line", { x1: x(t0 + hh * 3600), x2: x(t0 + hh * 3600), y1: top, y2: top + laneH, class: "vl-grid" }));
    const label = node("text", { x: 8, y: top + 15, class: "vl-label" });
    label.textContent = `${lane.name} · ${lane.unit}`;
    svg.append(label);
    // one path, broken where a minute has no reading (more than two minutes apart)
    let d = "", prev = null;
    for (const m of pts) {
      const px = x(m.t).toFixed(1), py = y(m[lane.key]).toFixed(1);
      d += prev != null && m.t - prev <= 120 ? `L${px} ${py}` : `M${px} ${py}`;
      prev = m.t;
    }
    if (d) svg.append(node("path", { d, class: `vl-line ${lane.cls}` }));
    // a lone minute still shows: a dot for each point that has no neighbour
    pts.forEach((m, j) => {
      const near = (k) => pts[k] && Math.abs(pts[k].t - m.t) <= 120;
      if (!near(j - 1) && !near(j + 1)) svg.append(node("circle", { cx: x(m.t), cy: y(m[lane.key]), r: 2.2, class: `vl-dot ${lane.cls}` }));
    });
    if (vals.length) {
      const avg = vals.reduce((a, b) => a + b, 0) / vals.length;
      desc.push(`${lane.name}: ${vals.length} minutes measured, from ${Math.round(Math.min(...vals))} to ${Math.round(Math.max(...vals))} ${lane.unit}, about ${Math.round(avg)} on average.`);
    } else {
      desc.push(`${lane.name}: nothing measured in the last ${HOURS} hours.`);
    }
  });
  $("vitals-desc").textContent = desc.join(" ");
  const axis = $("vitals-axis");
  axis.replaceChildren();
  for (let hh = 0; hh <= HOURS; hh += 6) {
    const t = t0 + hh * 3600;
    const span = el("span", null, hh === HOURS ? "now" : fmtHour(new Date(t * 1000).getHours()));
    span.style.left = `${(hh / HOURS) * 100}%`;
    axis.append(span);
  }
}

export function setup() {
  on("vitals", (c) => {
    if (v.available === false) v.available = true;      // the stream says there is one after all
    v.current = c;
    if (visible()) render();
  });
  on("view", ({ view }) => {
    if (view === "home") load().then(schedule);
    else clearTimeout(v.timer);
  });
  on("theme", () => { if (visible()) drawChart(); });
  on("resize", () => { if (visible()) drawChart(); });
  on("reconnected", () => { if (visible()) load(); });
  document.addEventListener("visibilitychange", () => { if (visible()) load().then(schedule); });
}

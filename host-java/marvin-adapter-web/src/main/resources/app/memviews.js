// SPDX-License-Identifier: MIT
// "See your memory": four pictures of what Marvin remembers, on real data only (GET /api/memory/graph, map,
// timeline, flow). A graph of who and what the facts are about, a meaning map (the facts' embeddings projected to
// two dimensions on the host), a timeline of when each fact held, and the flow from the log to the profile. Plain
// SVG, no library; each picture has one tab stop, arrow keys inside it, and the same content as a list.

import { $, SVG, app, el, api, on, plural, whenLabel, phone, reduceMotion, local } from "./core.js";
import { openFact, statusPill, sensitivityPill, sourceLine } from "./memdata.js";
import { passSummary } from "./worker.js";

const TABS = ["graph", "map", "timeline", "flow"];
const INTRO = {
  graph: "You at the centre; the people, places and things your memories are about around you. A line is one or more facts.",
  map: "Each fact is a point, placed by what it means: facts about the same thing gather. Computed on this computer.",
  timeline: "When each fact was true: from when it began, or was learned, to when it ended, or now.",
  flow: "How memory is built: moments are read by the worker, become facts, and the facts shape your profile.",
};
const KINDS = ["preference", "relation", "plan", "habit", "biographical", "state"];
const KIND_WORDS = { preference: "Preference", relation: "Relation", plan: "Plan", habit: "Habit", biographical: "Biographical", state: "State" };
const TYPES = ["owner", "person", "place", "thing"];
const TYPE_WORDS = { owner: "You", person: "Person", place: "Place", thing: "Thing" };
// a second, shape-based encoding, so that a kind never rests on colour alone
const SHAPES = {
  preference: "M0,-5.5 A5.5,5.5 0 1 1 0,5.5 A5.5,5.5 0 1 1 0,-5.5Z",
  relation: "M-4.8,-4.8 H4.8 V4.8 H-4.8Z",
  plan: "M0,-6 L5.6,4.4 H-5.6Z",
  habit: "M0,-6.4 L6.4,0 L0,6.4 L-6.4,0Z",
  biographical: "M0,6 L5.6,-4.4 H-5.6Z",
  state: "M-2,-6 H2 V-2 H6 V2 H2 V6 H-2 V2 H-6 V-2 H-2Z",
};
const SOURCE_WORDS = { conversation: "Conversations", brain: "What Marvin noticed", owner: "What you did here" };

let tab = local.get("marvin.memviews", "graph");
if (!TABS.includes(tab)) tab = "graph";
let range = "month";
let colourBy = "kind";
let seq = 0;
let reloadTimer = 0;
let resizeTimer = 0;
const data = {};

// ------------------------------------------------------------------ helpers

function s(tag, attrs = {}, cls) {
  const e = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) if (v != null) e.setAttribute(k, String(v));
  if (cls) e.setAttribute("class", cls);
  return e;
}

function hash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

/** A small seeded generator: the same memory gives the same picture. */
function seeded(seed) {
  let a = seed || 1;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const shortDate = (ts) => new Date(ts * 1000).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
const clip = (text, n) => (text.length > n ? `${text.slice(0, Math.max(1, n - 1)).trimEnd()}…` : text);
const typeOf = (subject) => (subject === "owner" ? "owner" : (subject.split(":")[0] || "thing"));
const subjectName = (subject) => (subject === "owner" ? "You" : subject.slice(subject.indexOf(":") + 1));

function figureWidth(box) {
  return Math.max(260, Math.round(box.getBoundingClientRect().width || box.parentElement.getBoundingClientRect().width || 600));
}

function empty(box, title, text) {
  const d = el("div", "empty mv-empty");
  d.append(el("h3", null, title), el("p", "small", text));
  box.replaceChildren(d);
}

function status(text) {
  $("mv-status").textContent = text;
}

/** One tooltip per figure, drawn over it; the focused mark carries the same words in its label. */
function tip(box) {
  let t = box.querySelector(".mv-tip");
  if (!t) {
    t = el("div", "mv-tip");
    t.setAttribute("aria-hidden", "true");
    t.hidden = true;
    box.append(t);
  }
  return {
    show(lines, x, y) {
      t.replaceChildren(...lines.map((l, i) => el(i === 0 ? "strong" : "span", null, l)));
      t.hidden = false;
      const bw = box.clientWidth, tw = Math.min(300, bw - 16);
      t.style.maxWidth = `${tw}px`;
      const r = t.getBoundingClientRect();
      let left = x + 14, top = y + 14;
      if (left + r.width > bw - 8) left = Math.max(8, x - r.width - 14);
      if (top + r.height > box.clientHeight - 4) top = Math.max(4, y - r.height - 14);
      t.style.left = `${left}px`;
      t.style.top = `${top + box.scrollTop}px`;
    },
    hide() { t.hidden = true; },
  };
}

/** The mark's position in the figure's box, for the tooltip. */
function boxPoint(box, mark) {
  const a = mark.getBoundingClientRect(), b = box.getBoundingClientRect();
  return [a.left - b.left + a.width / 2, a.top - b.top + a.height / 2];
}

/**
 * One tab stop for a figure's marks: arrow keys move to the nearest mark in that direction (or a custom move),
 * Enter and Space act, Home goes to the first. Returns a function that focuses a mark.
 */
function roving(root, marks, { onFocus, onAct, move }) {
  marks.forEach((m, i) => m.setAttribute("tabindex", i === 0 ? "0" : "-1"));
  const centre = (m) => { const r = m.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; };
  const focusMark = (m) => {
    if (!m) return;
    for (const x of marks) x.setAttribute("tabindex", x === m ? "0" : "-1");
    m.focus({ preventScroll: false });
  };
  for (const m of marks) {
    m.addEventListener("focus", () => { for (const x of marks) x.setAttribute("tabindex", x === m ? "0" : "-1"); onFocus(m); });
    m.addEventListener("click", () => onAct(m));
    m.addEventListener("keydown", (ev) => {
      if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); onAct(m); return; }
      if (ev.key === "Home") { ev.preventDefault(); focusMark(marks[0]); return; }
      if (ev.key === "End") { ev.preventDefault(); focusMark(marks[marks.length - 1]); return; }
      const dir = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[ev.key];
      if (!dir) return;
      ev.preventDefault();
      if (move) { focusMark(move(m, ev.key)); return; }
      const [x0, y0] = centre(m);
      let best = null, bestScore = Infinity;
      for (const o of marks) {
        if (o === m) continue;
        const [x, y] = centre(o);
        const dx = x - x0, dy = y - y0;
        const along = dx * dir[0] + dy * dir[1];
        if (along <= 0.5) continue;
        const across = Math.abs(dx * dir[1] - dy * dir[0]);
        const score = along + 2.2 * across;
        if (score < bestScore) { bestScore = score; best = o; }
      }
      focusMark(best);
    });
  }
  root.addEventListener("focusout", (ev) => { if (!root.contains(ev.relatedTarget)) onFocus(null); });
  return focusMark;
}

function factRow(f) {
  const li = el("li", "mv-fact");
  const tags = el("div", "fact-tags");
  tags.append(statusPill(f));
  const sp = sensitivityPill(f);
  if (sp) tags.append(sp);
  const h = el("p", "mv-fact-text", f.statement);
  h.id = `mv-f-${f.id}`;
  const b = el("button", "small-button", "Source & edit ↗");
  b.type = "button";
  b.setAttribute("aria-describedby", h.id);
  b.addEventListener("click", () => openFact(f.id));
  li.append(tags, h, el("p", "source", `${sourceLine(f)} · ${f.when}`), b);
  return li;
}

function factFlags(f) {
  const out = [];
  if (f.status === "past") out.push("no longer true");
  else if (f.status === "replaced") out.push("replaced by a newer wording");
  else if (f.archived) out.push("archived");
  if (f.pinned) out.push("pinned");
  if (f.sensitivity === "sensitive") out.push("sensitive");
  return out;
}

// ------------------------------------------------------------------ graph

const graphView = { zoom: 1, cx: 0, cy: 0, base: null, svg: null, selected: null };

function nodeRadius(n) {
  return n.type === "owner" ? 24 : Math.min(26, 9 + 4 * Math.sqrt(n.facts));
}

/** A small force layout: seeded, capped in iterations, the owner held at the centre. Computed once, not animated. */
function layoutGraph(nodes, edges, compact) {
  const rnd = seeded(hash(nodes.map((n) => n.id).join("|")));
  const index = new Map(nodes.map((n, i) => [n.id, i]));
  const R = nodes.map(nodeRadius);
  const P = nodes.map((n, i) => {
    if (i === 0) return { x: 0, y: 0 };
    const a = i * 2.399963 + rnd() * 0.3, r = 80 + 26 * Math.sqrt(i) + rnd() * 12;
    return { x: Math.cos(a) * r, y: Math.sin(a) * r };
  });
  const E = edges.map((e) => [index.get(e.from), index.get(e.to)]).filter(([a, b]) => a != null && b != null);
  const ITER = 320;
  for (let it = 0; it < ITER; it++) {
    const alpha = 1 - it / ITER;
    const F = P.map(() => ({ x: 0, y: 0 }));
    for (let i = 0; i < P.length; i++) {
      for (let j = i + 1; j < P.length; j++) {
        let dx = P[j].x - P[i].x, dy = P[j].y - P[i].y;
        let d2 = dx * dx + dy * dy;
        if (d2 < 0.01) { dx = rnd() - 0.5; dy = rnd() - 0.5; d2 = dx * dx + dy * dy; }
        const d = Math.sqrt(d2);
        let f = (compact ? 2600 : 5200) / d2;
        const min = R[i] + R[j] + 26;
        if (d < min) f += (min - d) * 0.5;
        F[i].x -= (dx / d) * f; F[i].y -= (dy / d) * f;
        F[j].x += (dx / d) * f; F[j].y += (dy / d) * f;
      }
    }
    for (const [a, b] of E) {
      const dx = P[b].x - P[a].x, dy = P[b].y - P[a].y;
      const d = Math.max(1, Math.hypot(dx, dy));
      const f = (d - ((compact ? 40 : 70) + R[a] + R[b])) * 0.06;
      F[a].x += (dx / d) * f; F[a].y += (dy / d) * f;
      F[b].x -= (dx / d) * f; F[b].y -= (dy / d) * f;
    }
    for (let i = 1; i < P.length; i++) {
      F[i].x -= P[i].x * 0.012; F[i].y -= P[i].y * 0.016;       // a little wider than tall
      const step = Math.hypot(F[i].x, F[i].y), max = 2 + 14 * alpha;
      const k = step > max ? max / step : 1;
      P[i].x += F[i].x * k; P[i].y += F[i].y * k;
    }
  }
  return P;
}

function setViewBox() {
  const g = graphView;
  if (!g.svg || !g.base) return;
  const w = g.base.w / g.zoom, h = g.base.h / g.zoom;
  g.svg.setAttribute("viewBox", `${g.cx - w / 2} ${g.cy - h / 2} ${w} ${h}`);
}

function renderGraph() {
  const box = $("mv-graph-figure"), d = data.graph;
  const nodes = d.nodes, edges = d.edges, facts = d.facts;
  const nfacts = Object.keys(facts).length;
  status(`${plural(nfacts, "fact", "facts")} · ${plural(nodes.length - 1, "subject", "subjects")} around you`);
  renderGraphList(d);
  if (nodes.length <= 1) {
    $("mv-graph-legend").textContent = "";
    graphView.svg = null;
    empty(box, nfacts ? "Only about you, so far." : "Nothing to draw yet.",
      nfacts ? "Every fact is about you; once Marvin learns about people, places or things in your life, they appear around you."
        : "Marvin draws this from the facts it keeps. Talk with it, or add a memory yourself.");
    return;
  }
  const P = layoutGraph(nodes, edges, phone.matches);
  const byId = new Map(nodes.map((n, i) => [n.id, { n, p: P[i], r: nodeRadius(n) }]));
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const { p, r } of byId.values()) {
    x0 = Math.min(x0, p.x - r - 50); x1 = Math.max(x1, p.x + r + 50);
    y0 = Math.min(y0, p.y - r - 10); y1 = Math.max(y1, p.y + r + 30);
  }
  const MAX_SCALE = 1.5;
  const W = figureWidth(box);
  const scale = Math.min(MAX_SCALE, W / (x1 - x0));
  const H = Math.min(phone.matches ? 440 : 560, Math.max(phone.matches ? 260 : 360, Math.round((y1 - y0) * scale + 20)));
  // fit the drawing to the box, the aspect kept, never enlarged more than MAX_SCALE (a few nodes stay modest)
  let bw = Math.max(x1 - x0, W / MAX_SCALE), bh = Math.max(y1 - y0, H / MAX_SCALE);
  if (bw / bh < W / H) bw = bh * (W / H); else bh = bw * (H / W);
  graphView.base = { w: bw, h: bh };
  graphView.cx = (x0 + x1) / 2; graphView.cy = (y0 + y1) / 2; graphView.zoom = 1;
  graphView.home = { cx: graphView.cx, cy: graphView.cy };

  const svgEl = s("svg", { width: "100%", height: H, role: "group", "aria-label": `Memory graph: you and ${plural(nodes.length - 1, "subject", "subjects")}, linked by ${plural(nfacts, "fact", "facts")}. Arrow keys move between nodes; Enter shows a node's facts.` }, "mv-svg mv-graph-svg");
  graphView.svg = svgEl;
  setViewBox();
  const bg = s("rect", { x: -1e5, y: -1e5, width: 2e5, height: 2e5 }, "mv-bg");
  const gEdges = s("g", {}, "mv-edges"), gNodes = s("g", {}, "mv-nodes");
  svgEl.append(bg, gEdges, gNodes);
  const t = tip(box);
  const edgeEls = [];
  for (const e of edges) {
    const a = byId.get(e.from), b = byId.get(e.to);
    if (!a || !b) continue;
    const w = Math.min(6, 1.2 + Math.sqrt(e.facts.length) * 0.9);
    const line = s("line", { x1: a.p.x, y1: a.p.y, x2: b.p.x, y2: b.p.y, "stroke-width": w }, `mv-edge${e.current ? "" : " faded"}${e.mention ? " mention" : ""}`);
    const hit = s("line", { x1: a.p.x, y1: a.p.y, x2: b.p.x, y2: b.p.y }, "mv-edge-hit");
    const statements = e.facts.map((id) => facts[id]).filter(Boolean).map((f) => f.statement);
    hit.addEventListener("pointerenter", (ev) => {
      line.classList.add("on");
      const r = box.getBoundingClientRect();
      t.show([`${a.n.name} — ${b.n.name}`, ...statements.slice(0, 4), ...(statements.length > 4 ? [`and ${statements.length - 4} more`] : [])],
        ev.clientX - r.left, ev.clientY - r.top);
    });
    hit.addEventListener("pointerleave", () => { line.classList.remove("on"); t.hide(); });
    gEdges.append(line, hit);
    edgeEls.push({ e, line });
  }
  const marks = [];
  for (const { n, p, r } of byId.values()) {
    const links = edges.filter((e) => e.from === n.id || e.to === n.id);
    const others = links.map((e) => byId.get(e.from === n.id ? e.to : e.from)).filter(Boolean).map((o) => o.n.name);
    const flags = [n.pinned && "pinned", n.sensitive && "has sensitive facts", n.past && "only past facts"].filter(Boolean);
    const label = `${n.name}, ${TYPE_WORDS[n.type].toLowerCase()}, ${plural(n.facts, "fact", "facts")}${flags.length ? `, ${flags.join(", ")}` : ""}.${others.length ? ` Linked to ${others.join(", ")}.` : ""}`;
    const g = s("g", { transform: `translate(${p.x.toFixed(1)},${p.y.toFixed(1)})`, role: "button", "aria-label": label },
      `mv-node t-${n.type}${n.past ? " faded" : ""}`);
    g.dataset.id = n.id;
    g.append(s("circle", { r: r + 10 }, "mv-node-hit"));
    if (n.pinned) g.append(s("circle", { r: r + 4 }, "mv-pin-ring"));
    if (n.type === "place") g.append(s("rect", { x: -r, y: -r, width: 2 * r, height: 2 * r, rx: r * 0.35 }, "mv-node-shape"));
    else if (n.type === "thing") g.append(s("path", { d: `M0,${-r * 1.18} L${r * 1.18},0 L0,${r * 1.18} L${-r * 1.18},0Z` }, "mv-node-shape"));
    else g.append(s("circle", { r }, "mv-node-shape"));
    if (n.sensitive) g.append(s("circle", { cx: r * 0.72, cy: -r * 0.72, r: 4.2 }, "mv-sensitive-dot"));
    if (n.type !== "owner") {
      const name = s("text", { y: r + 16, "text-anchor": "middle" }, "mv-node-label");
      name.textContent = clip(n.name, 18);
      g.append(name);
    }
    if (n.type !== "owner" || n.facts) {
      const c = s("text", { y: 4, "text-anchor": "middle" }, "mv-node-count");
      c.textContent = n.type === "owner" ? "You" : String(n.facts);
      if (n.type === "owner" || r >= 12) g.append(c);
    }
    gNodes.append(g);
    marks.push(g);
  }
  const highlight = graphView.highlight = (id) => {
    for (const { e, line } of edgeEls) line.classList.toggle("on", !!id && (e.from === id || e.to === id));
    for (const m of marks) m.classList.toggle("dim", !!id && m.dataset.id !== id
      && !edges.some((e) => (e.from === id && e.to === m.dataset.id) || (e.to === id && e.from === m.dataset.id)));
  };
  roving(svgEl, marks, {
    onFocus: (m) => {
      if (!m) { highlight(graphView.selected); t.hide(); return; }
      const n = byId.get(m.dataset.id).n;
      highlight(n.id);
      const links = edges.filter((e) => e.from === n.id || e.to === n.id);
      const lines = [`${n.name} · ${plural(n.facts, "fact", "facts")}`];
      for (const e of links.slice(0, 3)) {
        const f = facts[e.facts[0]];
        if (f) lines.push(`${clip(f.statement, 70)}${e.facts.length > 1 ? ` (+${e.facts.length - 1})` : ""}`);
      }
      if (links.length > 3) lines.push(`and ${plural(links.length - 3, "more link", "more links")}`);
      const [x, y] = boxPoint(box, m.querySelector(".mv-node-shape"));
      t.show(lines, x, y);
    },
    onAct: (m) => openSheet(byId.get(m.dataset.id).n),
  });
  for (const m of marks) {
    m.addEventListener("pointerenter", () => { if (document.activeElement !== m) { highlight(m.dataset.id); const [x, y] = boxPoint(box, m.querySelector(".mv-node-shape")); t.show([`${byId.get(m.dataset.id).n.name} · ${plural(byId.get(m.dataset.id).n.facts, "fact", "facts")}`, "Select to see its facts"], x, y); } });
    m.addEventListener("pointerleave", () => { if (document.activeElement !== m) { highlight(graphView.selected); t.hide(); } });
  }
  panning(svgEl);
  box.replaceChildren(svgEl, ...box.querySelectorAll(".mv-tip"));
  if (graphView.selected && byId.has(graphView.selected)) { highlight(graphView.selected); openSheet(byId.get(graphView.selected).n, false); } else closeSheet(false);
  $("mv-graph-legend").replaceChildren(...legendItems([
    ["t-owner", "circle", "You"], ["t-person", "circle", "Person"], ["t-place", "square", "Place"], ["t-thing", "diamond", "Thing"],
  ]), el("span", "mv-key-text", `Size: how many facts. Ring: pinned. Dot: sensitive. Faded: no longer true or archived.${d.hidden_nodes ? ` ${plural(d.hidden_nodes, "smaller subject is", "smaller subjects are")} left out; the list below has them all.` : ""}`));
}

function legendItems(items) {
  return items.map(([cls, shape, text]) => {
    const i = el("span", "mv-key");
    const k = s("svg", { width: 14, height: 14, viewBox: "-7 -7 14 14", "aria-hidden": "true" }, `mv-key-mark ${cls}`);
    if (shape === "square") k.append(s("rect", { x: -5, y: -5, width: 10, height: 10, rx: 3 }));
    else if (shape === "diamond") k.append(s("path", { d: "M0,-6 L6,0 L0,6 L-6,0Z" }));
    else if (shape === "line") k.append(s("rect", { x: -7, y: -2.5, width: 14, height: 5, rx: 2 }));
    else if (SHAPES[shape]) k.append(s("path", { d: SHAPES[shape] }));
    else k.append(s("circle", { r: 5 }));
    i.append(k, text);
    return i;
  });
}

/** Drag to move the picture when zoomed in; the zoom buttons zoom around the centre. */
function panning(svgEl) {
  let from = null;
  svgEl.addEventListener("pointerdown", (ev) => {
    if (!ev.target.classList.contains("mv-bg") || graphView.zoom <= 1) return;
    from = { x: ev.clientX, y: ev.clientY, cx: graphView.cx, cy: graphView.cy };
    svgEl.setPointerCapture(ev.pointerId);
    svgEl.classList.add("panning");
  });
  svgEl.addEventListener("pointermove", (ev) => {
    if (!from) return;
    const k = graphView.base.w / graphView.zoom / svgEl.getBoundingClientRect().width;
    graphView.cx = from.cx - (ev.clientX - from.x) * k;
    graphView.cy = from.cy - (ev.clientY - from.y) * k;
    setViewBox();
  });
  const end = () => { from = null; svgEl.classList.remove("panning"); };
  svgEl.addEventListener("pointerup", end);
  svgEl.addEventListener("pointercancel", end);
}

function zoom(how) {
  const g = graphView;
  if (!g.svg) return;
  if (how === "in") g.zoom = Math.min(4, g.zoom * 1.4);
  else if (how === "out") g.zoom = Math.max(1, g.zoom / 1.4);
  else g.zoom = 1;
  if (g.zoom === 1) { g.cx = g.home.cx; g.cy = g.home.cy; }
  setViewBox();
  g.svg.classList.toggle("zoomed", g.zoom > 1);
}

function nodeFacts(n) {
  const d = data.graph;
  const ids = new Set();
  for (const [id, f] of Object.entries(d.facts)) if (f.node === n.id) ids.add(id);
  for (const e of d.edges) if (e.from === n.id || e.to === n.id) {
    if (n.type === "owner" && !e.mention) continue;       // the owner's links are the other nodes' facts
    e.facts.forEach((id) => ids.add(id));
  }
  return [...ids].map((id) => d.facts[id]).filter(Boolean)
    .sort((a, b) => (a.status === "current" && !a.archived ? 0 : 1) - (b.status === "current" && !b.archived ? 0 : 1) || b.learned_at - a.learned_at);
}

function openSheet(n, focus = true) {
  graphView.selected = n.id;
  const facts = nodeFacts(n);
  const opened = $("mv-sheet").hidden;
  $("mv-sheet").hidden = false;
  if (opened && !phone.matches) renderGraph();      // beside the sheet, the graph has less room: drawn again to fit
  $("mv-sheet-title").textContent = n.type === "owner" ? "About you" : n.name;
  $("mv-sheet-meta").textContent = `${TYPE_WORDS[n.type]} · ${plural(facts.length, "fact", "facts")}${n.pinned ? " · pinned" : ""}${n.sensitive ? " · sensitive facts" : ""}`;
  $("mv-sheet-facts").replaceChildren(...facts.map(factRow));
  if (focus) {
    if (phone.matches) $("mv-sheet").scrollIntoView({ block: "nearest", behavior: reduceMotion.matches ? "auto" : "smooth" });
    $("mv-sheet-title").focus({ preventScroll: true });
  }
}

function closeSheet(refocus = true) {
  const id = graphView.selected;
  graphView.selected = null;
  const was = !$("mv-sheet").hidden;
  $("mv-sheet").hidden = true;
  if (graphView.highlight) graphView.highlight(null);
  if (was && !phone.matches && data.graph) renderGraph();
  if (refocus && id && graphView.svg) {
    const m = [...graphView.svg.querySelectorAll(".mv-node")].find((x) => x.dataset.id === id);
    if (m) m.focus();
  }
}

function renderGraphList(d) {
  const ul = el("ul", "mv-text-list");
  const facts = Object.values(d.facts);
  for (const n of d.nodes) {
    const li = el("li");
    const mine = nodeFacts(n);
    li.append(el("strong", null, `${n.type === "owner" ? "You" : n.name} (${TYPE_WORDS[n.type].toLowerCase()}), ${plural(mine.length, "fact", "facts")}`));
    const inner = el("ul");
    for (const f of mine) inner.append(el("li", null, `${f.statement}${factFlags(f).length ? ` (${factFlags(f).join(", ")})` : ""}`));
    li.append(inner);
    ul.append(li);
  }
  const shown = new Set(d.nodes.map((n) => n.id));
  const rest = facts.filter((f) => !shown.has(f.node));
  const box = $("mv-graph-list");
  box.replaceChildren(ul);
  if (rest.length) box.append(el("p", "small muted", `${plural(rest.length, "fact", "facts")} about smaller subjects: ${rest.map((f) => f.statement).join(" · ")}`));
}

// ------------------------------------------------------------------ meaning map

function renderMap() {
  const box = $("mv-map-figure"), d = data.map;
  const pts = d.points;
  renderUnplaced(d);
  renderMapList(d);
  const [e1, e2] = d.explained || [0, 0];
  status(`${plural(pts.length, "fact", "facts")} placed${d.unplaced.length ? ` · ${d.unplaced.length} not placed yet` : ""}`);
  if (!pts.length) {
    $("mv-map-legend").textContent = "";
    empty(box, d.unplaced.length ? "Nothing placed yet." : "Nothing to place yet.",
      d.unplaced.length ? "Your facts wait for their embeddings: see below." : "The map places current facts by their meaning. Talk with Marvin, or add a memory.");
    return;
  }
  const W = figureWidth(box), H = phone.matches ? 340 : Math.min(480, Math.max(340, Math.round(W * 0.5)));
  const pad = 26;
  const xs = pts.map((p) => p.x), ys = pts.map((p) => p.y);
  const xmin = Math.min(...xs), xmax = Math.max(...xs), ymin = Math.min(...ys), ymax = Math.max(...ys);
  // one scale for both axes: distances on screen stay true to distances in meaning
  const k = Math.min((W - 2 * pad) / Math.max(1e-9, xmax - xmin || 1), (H - 2 * pad) / Math.max(1e-9, ymax - ymin || 1));
  const kk = Number.isFinite(k) && xmax - xmin + ymax - ymin > 1e-9 ? k : 0;
  const px = (x) => W / 2 + (x - (xmin + xmax) / 2) * kk;
  const py = (y) => H / 2 - (y - (ymin + ymax) / 2) * kk;
  const svgEl = s("svg", { width: "100%", height: H, viewBox: `0 0 ${W} ${H}`, role: "group",
    "aria-label": `Meaning map of ${plural(pts.length, "fact", "facts")}. Arrow keys move to the nearest fact in that direction; Enter opens it.` }, "mv-svg mv-map-svg");
  svgEl.append(s("line", { x1: W / 2, y1: 8, x2: W / 2, y2: H - 8 }, "mv-axis"), s("line", { x1: 8, y1: H / 2, x2: W - 8, y2: H / 2 }, "mv-axis"));
  const t = tip(box);
  const marks = [];
  const order = pts.map((p, i) => i).sort((a, b) => pts[a].x - pts[b].x || pts[a].y - pts[b].y);
  for (const i of order) {
    const p = pts[i];
    const cls = colourBy === "kind" ? `k-${KINDS.includes(p.kind) ? p.kind : "state"}` : `t-${typeOf(p.subject)}`;
    const flags = factFlags(p);
    const who = p.subject === "owner" ? "" : ` About ${subjectName(p.subject)}.`;
    const g = s("g", { transform: `translate(${px(p.x).toFixed(1)},${py(p.y).toFixed(1)})`, role: "button",
      "aria-label": `${p.statement}${who} ${KIND_WORDS[p.kind] || p.kind}${flags.length ? `, ${flags.join(", ")}` : ""}.` }, `mv-point ${cls}`);
    g.dataset.id = p.id;
    g.append(s("circle", { r: 14 }, "mv-point-hit"));
    if (p.pinned) g.append(s("circle", { r: 11.5 }, "mv-pin-ring"));
    g.append(s("path", { d: SHAPES[p.kind] || SHAPES.state }, "mv-point-shape"));
    if (p.sensitivity === "sensitive") g.append(s("circle", { cx: 7, cy: -7, r: 3.4 }, "mv-sensitive-dot"));
    svgEl.append(g);
    marks.push(g);
  }
  // direct labels where they fit (never over another label): the list below has every fact
  if (pts.length <= 40 && W >= 480) {
    const taken = [];
    const labels = s("g", { "aria-hidden": "true" }, "mv-point-labels");
    for (const i of order) {
      const p = pts[i];
      const text = clip(p.statement.replace(/^The owner('s)? /, (m0) => (m0.includes("'s") ? "Your " : "")), 28);
      const tw = text.length * 6.1, cx = px(p.x), cy = py(p.y);
      for (const [lx, anchor] of [[cx + (p.pinned ? 15 : 11), "start"], [cx - (p.pinned ? 15 : 11), "end"]]) {
        const box0 = anchor === "start" ? [lx, cy - 9, lx + tw, cy + 5] : [lx - tw, cy - 9, lx, cy + 5];
        if (box0[0] < 4 || box0[2] > W - 4) continue;
        if (taken.some((r) => r[0] < box0[2] && box0[0] < r[2] && r[1] < box0[3] && box0[1] < r[3])) continue;
        if (pts.some((o) => o !== p && px(o.x) > box0[0] - 6 && px(o.x) < box0[2] + 6 && py(o.y) > box0[1] - 4 && py(o.y) < box0[3] + 4)) continue;
        taken.push(box0);
        const t0 = s("text", { x: lx, y: cy + 4, "text-anchor": anchor }, "mv-point-label");
        t0.textContent = text;
        labels.append(t0);
        break;
      }
    }
    svgEl.insertBefore(labels, svgEl.querySelector(".mv-point"));
  }
  const byId = new Map(pts.map((p) => [p.id, p]));
  const show = (m) => {
    const p = byId.get(m.dataset.id);
    const [x, y] = boxPoint(box, m.querySelector(".mv-point-shape"));
    t.show([p.statement, `${KIND_WORDS[p.kind] || p.kind}${p.subject !== "owner" ? ` · ${subjectName(p.subject)}` : ""}${factFlags(p).length ? ` · ${factFlags(p).join(", ")}` : ""}`], x, y);
  };
  roving(svgEl, marks, { onFocus: (m) => { if (m) show(m); else t.hide(); }, onAct: (m) => openFact(m.dataset.id) });
  for (const m of marks) {
    m.addEventListener("pointerenter", () => show(m));
    m.addEventListener("pointerleave", () => { if (document.activeElement !== m) t.hide(); });
  }
  box.replaceChildren(svgEl, ...box.querySelectorAll(".mv-tip"));
  const legend = colourBy === "kind"
    ? KINDS.filter((kd) => pts.some((p) => p.kind === kd)).map((kd) => [`k-${kd}`, kd, KIND_WORDS[kd]])
    : TYPES.filter((ty) => pts.some((p) => typeOf(p.subject) === ty)).map((ty) => [`t-${ty}`, "circle", TYPE_WORDS[ty]]);
  const note = pts.length === 1 ? "One fact, at the centre: a map needs a few facts to compare."
    : pts.length === 2 ? "Two facts: a line for now; the map spreads out with more."
      : `The two directions hold ${Math.round(e1 * 100)}% and ${Math.round(e2 * 100)}% of the differences between your facts; they have no names of their own.`;
  $("mv-map-legend").replaceChildren(...legendItems(legend), el("span", "mv-key-text", `Ring: pinned. Dot: sensitive. ${note}`));
}

function renderUnplaced(d) {
  const box = $("mv-unplaced");
  if (!d.unplaced.length) { box.hidden = true; return; }
  box.hidden = false;
  const e = d.embeddings || {};
  const why = e.state === "unavailable"
    ? `the embedding model (${e.model}) is not available${e.fix ? `. Fix: ${e.fix.replace(/\.+$/, "")}` : ""}`
    : "the embedding model was missing when they were written, or they are being embedded again";
  const ul = el("ul", "mv-text-list");
  for (const f of d.unplaced.slice(0, 20)) ul.append(el("li", null, f.statement));
  box.replaceChildren(el("h4", null, `Not placed yet: ${plural(d.unplaced.length, "fact", "facts")}`),
    el("p", "small muted", `They have no embedding yet: ${why}. They appear on the map once embedded.`), ul);
  if (d.unplaced.length > 20) box.append(el("p", "small muted", `and ${d.unplaced.length - 20} more`));
}

function renderMapList(d) {
  const box = $("mv-map-list");
  const groups = colourBy === "kind" ? KINDS.map((kd) => [KIND_WORDS[kd], d.points.filter((p) => p.kind === kd)])
    : TYPES.map((ty) => [TYPE_WORDS[ty], d.points.filter((p) => typeOf(p.subject) === ty)]);
  const ul = el("ul", "mv-text-list");
  for (const [name, list] of groups) {
    if (!list.length) continue;
    const li = el("li");
    li.append(el("strong", null, `${name}: ${plural(list.length, "fact", "facts")}`));
    const inner = el("ul");
    // in map order, left to right, so that neighbours in the list are neighbours in meaning along the first direction
    for (const p of list.slice().sort((a, b) => a.x - b.x)) inner.append(el("li", null, `${p.statement}${factFlags(p).length ? ` (${factFlags(p).join(", ")})` : ""}`));
    li.append(inner);
    ul.append(li);
  }
  box.replaceChildren(ul);
  if (d.unplaced.length) box.append(el("p", "small muted", `Not placed yet: ${d.unplaced.map((f) => f.statement).join(" · ")}`));
}

// ------------------------------------------------------------------ timeline

function ticks(from, to, W) {
  const span = to - from, out = [];
  const d0 = new Date(from * 1000);
  let step;
  if (span <= 9 * 86400) step = "day";
  else if (span <= 45 * 86400) step = W < 500 ? "week" : "day2";
  else if (span <= 400 * 86400) step = W < 500 ? "quarter" : "month";
  else step = "year";
  let d = new Date(d0.getFullYear(), d0.getMonth(), d0.getDate());
  if (step === "week") d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  if (step === "month" || step === "quarter") d = new Date(d.getFullYear(), d.getMonth(), 1);
  if (step === "year") d = new Date(d.getFullYear(), 0, 1);
  for (let i = 0; i < 400; i++) {
    const ts = d.getTime() / 1000;
    if (ts > to) break;
    if (ts >= from) {
      const label = step === "year" ? String(d.getFullYear())
        : step === "month" || step === "quarter" ? d.toLocaleDateString("en-GB", { month: "short" }) + (d.getMonth() === 0 ? ` ${d.getFullYear()}` : "")
          : d.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
      out.push([ts, label]);
    }
    if (step === "day") d.setDate(d.getDate() + 1);
    else if (step === "day2") d.setDate(d.getDate() + 5);
    else if (step === "week") d.setDate(d.getDate() + 7);
    else if (step === "month") d.setMonth(d.getMonth() + 1);
    else if (step === "quarter") d.setMonth(d.getMonth() + 3);
    else d.setFullYear(d.getFullYear() + 1);
  }
  return out;
}

const END_WORDS = { valid_to: "ended", expired: "no longer believed", replaced: "reworded", open: "still true" };

function barWords(b) {
  const f = b.fact;
  const start = `${b.start_kind === "valid_from" ? "true from" : b.start_kind === "reworded" ? "worded so from" : "learned"} ${shortDate(b.start)}`;
  const end = b.end == null ? "still true" : `${END_WORDS[b.end_kind] || "ended"} ${shortDate(b.end)}`;
  const then = b.next_kind === "then" ? ", then replaced by the next fact" : b.next_kind === "reworded" ? ", then reworded" : "";
  return `${f.statement} (${start}, ${end}${then}${factFlags(f).filter((x) => x !== "replaced by a newer wording").length ? `; ${factFlags(f).filter((x) => x !== "replaced by a newer wording").join(", ")}` : ""})`;
}

function renderTimeline() {
  const box = $("mv-timeline-figure"), d = data.timeline;
  const lanes = d.lanes, eps = d.episodes;
  const nbars = lanes.reduce((n, l) => n + l.bars.length, 0);
  status(`${plural(nbars, "fact", "facts")} in ${plural(lanes.length, "lane", "lanes")}${d.truncated ? " (the most recent)" : ""} · ${plural(eps.length, "summary", "summaries")}`);
  renderTimelineList(d);
  if (!lanes.length && !eps.length) {
    $("mv-timeline-legend").textContent = "";
    empty(box, "Nothing in this range.", range === "all" ? "Marvin has no facts or summaries yet." : "No fact held and no day was summed up in this range. Try a longer one.");
    return;
  }
  const W = figureWidth(box);
  const from = d.from, to = d.to, now = d.to;
  const left = 8, right = W - 12;
  const x = (ts) => left + ((Math.min(Math.max(ts, from), to) - from) / (to - from)) * (right - left);
  const AXIS = 30, EP = eps.length ? 34 : 0, LANE = 36;
  const H = AXIS + EP + lanes.length * LANE + 12;
  const svgEl = s("svg", { width: "100%", height: H, viewBox: `0 0 ${W} ${H}`, role: "group",
    "aria-label": `Timeline from ${shortDate(from)} to now: ${plural(nbars, "fact", "facts")} and ${plural(eps.length, "summary", "summaries")}. Up and down move between lanes, left and right along a lane; Enter opens a fact.` }, "mv-svg mv-timeline-svg");
  for (const [ts, label] of ticks(from, to, W)) {
    const tx = x(ts);
    svgEl.append(s("line", { x1: tx, y1: AXIS - 6, x2: tx, y2: H }, "mv-grid"));
    const tl = s("text", { x: tx + 3, y: AXIS - 12 }, "mv-tick");
    tl.textContent = label;
    const nowX0 = x(now);
    // "now" keeps its place: a tick label that would run into it is left out
    if (tx + 3 + label.length * 6.6 < Math.min(W, nowX0 - 30) || tx > nowX0 + 4) svgEl.append(tl);
  }
  const t = tip(box);
  const rows = [];
  if (eps.length) {
    const row = [];
    const lab = s("text", { x: left, y: AXIS + 9 }, "mv-lane-label");
    lab.textContent = "Days and weeks, in Marvin’s words";
    svgEl.append(lab);
    for (const e of eps.slice().sort((a, b) => a.period_start - b.period_start)) {
      const week = e.level === "week";
      const x0 = x(e.period_start), x1 = Math.max(x0 + 3, x(e.period_end) - 1);
      if (x(e.period_end) <= left || x0 >= right) continue;
      const g = s("g", { role: "button", "aria-label": `${week ? "Week" : "Day"} of ${shortDate(e.period_start)}: ${e.summary || "being written again"}` }, `mv-episode${week ? " week" : ""}${e.stale ? " stale" : ""}`);
      g.dataset.words = `${week ? "Week of " : ""}${new Date(e.period_start * 1000).toLocaleDateString("en-GB", { weekday: week ? undefined : "short", day: "numeric", month: "short" })}`;
      g.dataset.summary = e.summary || "Being written again: something in it was forgotten or made private.";
      g.append(s("rect", { x: x0, y: AXIS + (week ? 24 : 14), width: x1 - x0, height: week ? 5 : 8, rx: 2 }, "mv-episode-bar"));
      g.append(s("rect", { x: x0, y: AXIS + 10, width: Math.max(10, x1 - x0), height: 22 }, "mv-hit"));
      svgEl.append(g);
      row.push(g);
    }
    if (row.length) rows.push(row);
  }
  lanes.forEach((lane, i) => {
    const y = AXIS + EP + i * LANE + 4;
    const row = [];
    let used = left;           // where the lane's previous caption ends
    lane.bars.forEach((b, j) => {
      const f = b.fact;
      const x1 = Math.max(x(b.start) + 4, x(b.end == null ? now : b.end));
      const x0 = Math.min(x(b.start), x1 - 6);        // a fact learned a moment ago is still a visible bar
      const faded = f.status !== "current" || f.archived;
      const kind = KINDS.includes(f.kind) ? f.kind : "state";
      const g = s("g", { role: "button", "aria-label": barWords(b) }, `mv-bar k-${kind}${faded ? " faded" : ""}${b.end == null ? " open" : ""}`);
      g.dataset.id = f.id;
      const by = y + 17;
      g.append(s("rect", { x: x0, y: by, width: x1 - x0, height: 9, rx: 4 }, "mv-bar-rect"));
      if (b.end == null) g.append(s("circle", { cx: x1, cy: by + 4.5, r: 4 }, "mv-now-dot"));
      if (f.pinned) g.append(s("rect", { x: x0 - 2, y: by - 2, width: x1 - x0 + 4, height: 13, rx: 6 }, "mv-pin-outline"));
      // the caption above the bar, from its start, moved left when the bar is near the end, up to the next caption
      const next = lane.bars[j + 1];
      const mark = f.sensitivity === "sensitive" ? 10 : 0;
      const want = f.statement.length * 6.2 + mark + 8;
      const limit = next ? Math.max(x(next.start), used + 60) : right;
      const lx = Math.max(used, Math.min(x0, right - want));
      const room = limit - lx - 6;
      if (room > 30) {
        if (mark) g.append(s("circle", { cx: lx + 4, cy: y + 7, r: 3.4 }, "mv-sensitive-dot"));
        const label = s("text", { x: lx + mark, y: y + 11 }, "mv-bar-label");
        label.textContent = clip(f.statement, Math.floor((room - mark) / 6.1));
        g.append(label);
        used = lx + Math.min(room, want) + 12;
      }
      g.append(s("rect", { x: Math.min(x0, lx), y: y - 2, width: Math.max(14, x1 - Math.min(x0, lx)), height: LANE - 4 }, "mv-hit"));
      if (b.next && next) svgEl.append(s("line", { x1: x1, y1: by + 4.5, x2: x(next.start), y2: by + 4.5 }, `mv-chain${b.next_kind === "then" ? " then" : ""}`));
      svgEl.append(g);
      row.push(g);
    });
    rows.push(row);
  });
  const nowX = x(now);
  svgEl.append(s("line", { x1: nowX, y1: AXIS - 8, x2: nowX, y2: H }, "mv-now-line"));
  const nl = s("text", { x: nowX - 4, y: AXIS - 12, "text-anchor": "end" }, "mv-tick now");
  nl.textContent = "now";
  svgEl.append(nl);
  const all = rows.flat();
  const barsById = new Map(lanes.flatMap((l) => l.bars).map((b) => [b.fact.id, b]));
  const where = (m) => { for (let r = 0; r < rows.length; r++) { const c = rows[r].indexOf(m); if (c >= 0) return [r, c]; } return [0, 0]; };
  const move = (m, key) => {
    const [r, c] = where(m);
    if (key === "ArrowLeft") return rows[r][c - 1] || m;
    if (key === "ArrowRight") return rows[r][c + 1] || m;
    const target = rows[r + (key === "ArrowDown" ? 1 : -1)];
    if (!target || !target.length) return m;
    const mx = m.getBoundingClientRect().left;
    return target.reduce((best, o) => (Math.abs(o.getBoundingClientRect().left - mx) < Math.abs(best.getBoundingClientRect().left - mx) ? o : best), target[0]);
  };
  const show = (m) => {
    const [px, py] = boxPoint(box, m.querySelector("rect"));
    if (m.dataset.id) {
      const b = barsById.get(m.dataset.id);
      t.show([b.fact.statement, barWords(b).slice(b.fact.statement.length + 2, -1)], px, py);
    } else t.show([m.dataset.words, clip(m.dataset.summary, 220)], px, py);
  };
  roving(svgEl, all, {
    move,
    onFocus: (m) => { if (m) { show(m); m.scrollIntoView({ block: "nearest", inline: "nearest" }); } else t.hide(); },
    onAct: (m) => { if (m.dataset.id) openFact(m.dataset.id); },
  });
  for (const m of all) {
    m.addEventListener("pointerenter", () => show(m));
    m.addEventListener("pointerleave", () => { if (document.activeElement !== m) t.hide(); });
  }
  box.replaceChildren(svgEl, ...box.querySelectorAll(".mv-tip"));
  const kinds = KINDS.filter((kd) => lanes.some((l) => l.bars.some((b) => b.fact.kind === kd)));
  $("mv-timeline-legend").replaceChildren(...legendItems(kinds.map((kd) => [`k-${kd}`, "line", KIND_WORDS[kd]])),
    el("span", "mv-key-text", "Faded: ended, replaced or archived. A lane is one fact and what followed it (a new wording, or the fact that replaced it). Dot at the end: still true."));
}

function renderTimelineList(d) {
  const box = $("mv-timeline-list");
  const ol = el("ol", "mv-text-list");
  for (const lane of d.lanes) {
    const li = el("li");
    li.append(el("span", null, lane.bars.map(barWords).join(" → ")));
    ol.append(li);
  }
  box.replaceChildren(el("h4", null, "Facts"), d.lanes.length ? ol : el("p", "small muted", "None in this range."));
  if (d.episodes.length) {
    const ul = el("ul", "mv-text-list");
    for (const e of d.episodes.slice().sort((a, b) => b.period_start - a.period_start)) {
      ul.append(el("li", null, `${e.level === "week" ? "Week of " : ""}${shortDate(e.period_start)}: ${e.summary || "being written again"}`));
    }
    box.append(el("h4", null, "Days and weeks, in Marvin’s words"), ul);
  }
}

// ------------------------------------------------------------------ flow

const STEP_STAGE = { extract: "read", embeddings: "facts", days: "written", "roll-ups": "written", profile: "written", decay: "facts", retention: "log" };
const STEP_WORDS = {
  extract: "reading what happened", embeddings: "indexing by meaning", days: "writing the days", "roll-ups": "writing the weeks",
  profile: "rewriting your profile", decay: "letting old things fade", retention: "tidying the old log",
};

function flowStages(d) {
  const f = d.facts, w = d.written;
  const read = d.sources.reduce((n, x) => n + x.events - x.waiting, 0);
  return [
    { id: "log", title: "Moments in the log", items: d.sources.map((x) => ({ id: `src-${x.source}`, label: SOURCE_WORDS[x.source] || x.source, n: x.events })) },
    { id: "read", title: "Read by the worker", items: [{ id: "waiting", label: "Waiting to be read", n: d.waiting, attention: d.waiting > 0 }, { id: "read", label: "Read", n: read }] },
    { id: "facts", title: "Facts", items: [
      { id: "current", label: "True now", n: f.current }, { id: "updated", label: "Earlier wordings", n: f.updated },
      { id: "invalidated", label: "No longer true", n: f.invalidated }, { id: "archived", label: "Archived", n: f.archived },
      { id: "forgotten", label: "Forgotten by you", n: f.forgotten, gone: true },
    ] },
    { id: "written", title: "Written from them", items: [
      { id: "profile", label: "Profile versions", n: w.profile_versions }, { id: "days", label: "Days summed up", n: w.days },
      { id: "weeks", label: "Weeks and months", n: w.weeks + w.months },
    ] },
  ];
}

function renderFlow() {
  const box = $("mv-flow-figure"), d = data.flow;
  renderNow(d.worker);
  renderPasses(d);
  renderFlowList(d);
  status(`${plural(d.sources.reduce((n, x) => n + x.events, 0), "moment", "moments")} · ${plural(d.facts.current, "fact", "facts")} true now`);
  const W = figureWidth(box);
  const vertical = W < 640;
  const stages = flowStages(d);
  const active = d.worker.state === "running" ? STEP_STAGE[d.worker.step] || "read" : null;
  const svgEl = s("svg", { width: "100%", role: "img", "aria-label": "How memory is built, step by step; the same numbers are in the list below." }, "mv-svg mv-flow-svg");
  const pos = new Map();
  const maxN = Math.max(1, ...stages.flatMap((st) => st.items.map((i) => i.n)));
  let H;
  if (!vertical) {
    const colW = (W - 30) / stages.length, boxW = Math.min(170, colW - 44);
    H = 320;
    stages.forEach((st, c) => {
      const cx = 15 + c * colW;
      const title = s("text", { x: cx, y: 16 }, `mv-stage-title${active === st.id ? " active" : ""}`);
      title.textContent = st.title;
      svgEl.append(title);
      const heights = st.items.map(() => 50);
      const total = heights.reduce((a, b) => a + b, 0) + (st.items.length - 1) * 8;
      let y = 32 + Math.max(0, (H - 40 - total) / 2);
      st.items.forEach((it, k) => {
        pos.set(it.id, { x: cx, y, w: boxW, h: heights[k] });
        y += heights[k] + 8;
      });
    });
  } else {
    // a phone: the stages one under the other, their items side by side
    let y = 0;
    const gap = 8;
    stages.forEach((st, c) => {
      if (c) {
        // one step leads to the next: a chevron between the stages
        svgEl.append(s("path", { d: `M${W / 2 - 7},${y - 20} l7,7 l7,-7` }, `mv-chevron${active === st.id ? " active" : ""}`));
      }
      const title = s("text", { x: 2, y: y + 14 }, `mv-stage-title${active === st.id ? " active" : ""}`);
      title.textContent = st.title;
      svgEl.append(title);
      y += 24;
      const per = Math.min(st.items.length, W < 440 ? 2 : 3);
      const bw = (W - 4 - gap * (per - 1)) / per;
      st.items.forEach((it, k) => {
        const col = k % per, row = Math.floor(k / per);
        pos.set(it.id, { x: 2 + col * (bw + gap), y: y + row * 58, w: bw, h: 50 });
      });
      y += Math.ceil(st.items.length / per) * 58 + 34;
    });
    H = y - 28;
  }
  svgEl.setAttribute("height", H);
  svgEl.setAttribute("viewBox", `0 0 ${W} ${H}`);
  // bands where the units match (moments → waiting or read), arrows where one thing becomes another
  const links = s("g", {}, "mv-links");
  svgEl.append(links);
  const read = pos.get("read"), waiting = pos.get("waiting");
  for (const src of d.sources) {
    const a = pos.get(`src-${src.source}`);
    if (!a || !src.events) continue;
    for (const [target, n] of [[waiting, src.waiting], [read, src.events - src.waiting]]) {
      if (!n) continue;
      const wdt = Math.max(1.5, 16 * Math.sqrt(n / maxN));
      const p = vertical
        ? `M${a.x + a.w / 2},${a.y + a.h} C${a.x + a.w / 2},${a.y + a.h + 30} ${target.x + target.w / 2},${target.y - 30} ${target.x + target.w / 2},${target.y}`
        : `M${a.x + a.w},${a.y + a.h / 2} C${a.x + a.w + 30},${a.y + a.h / 2} ${target.x - 30},${target.y + target.h / 2} ${target.x},${target.y + target.h / 2}`;
      links.append(s("path", { d: p, "stroke-width": wdt }, `mv-band${target === waiting ? " waiting" : ""}${active === "read" ? " active" : ""}`));
    }
  }
  const arrow = (a, b, on) => {
    if (!a || !b) return;
    const p = vertical
      ? `M${a.x + a.w / 2},${a.y + a.h + 2} L${a.x + a.w / 2},${b.y - 6}`
      : `M${a.x + a.w + 4},${a.y + a.h / 2} C${a.x + a.w + 26},${a.y + a.h / 2} ${b.x - 26},${b.y + b.h / 2} ${b.x - 6},${b.y + b.h / 2}`;
    links.append(s("path", { d: p }, `mv-arrow${on ? " active" : ""}`));
    const end = vertical ? [a.x + a.w / 2, b.y - 2] : [b.x - 2, b.y + b.h / 2];
    const tri = vertical ? `M${end[0] - 4},${end[1] - 6} L${end[0]},${end[1]} L${end[0] + 4},${end[1] - 6}Z` : `M${end[0] - 6},${end[1] - 4} L${end[0]},${end[1]} L${end[0] - 6},${end[1] + 4}Z`;
    links.append(s("path", { d: tri }, `mv-arrow-head${on ? " active" : ""}`));
  };
  if (!vertical) {
    arrow(read, pos.get("current"), active === "read" || active === "facts");
    arrow(pos.get("current"), pos.get("profile"), active === "written");
    arrow(pos.get("current"), pos.get("days"), active === "written");
  }
  const nodes = s("g", {}, "mv-flow-nodes");
  for (const st of stages) {
    for (const it of st.items) {
      const p = pos.get(it.id);
      const on = active === st.id;
      const g = s("g", {}, `mv-flow-node${on ? " active" : ""}${it.attention ? " attention" : ""}${it.gone ? " gone" : ""}${it.n ? "" : " zero"}`);
      g.append(s("rect", { x: p.x, y: p.y, width: p.w, height: p.h, rx: 10 }, "mv-flow-box"));
      if (it.n) g.append(s("rect", { x: p.x + 10, y: p.y + p.h - 7, width: Math.max(3, (p.w - 20) * Math.sqrt(it.n / maxN)), height: 3, rx: 1.5 }, "mv-flow-meter"));
      const n = s("text", { x: p.x + 10, y: p.y + 20 }, "mv-flow-n");
      n.textContent = it.n.toLocaleString("en-GB");
      const l = s("text", { x: p.x + 10, y: p.y + 36 }, "mv-flow-label");
      l.textContent = clip(it.label, Math.floor((p.w - 16) / 6.3));
      g.append(n, l);
      nodes.append(g);
    }
  }
  svgEl.append(nodes);
  box.replaceChildren(svgEl);
}

function renderNow(w) {
  const box = $("mv-now");
  box.replaceChildren();
  if (!w) return;
  const running = w.state === "running";
  const dot = el("span", `mv-live-dot${running ? " running" : ""}`);
  dot.setAttribute("aria-hidden", "true");
  let text;
  if (w.state === "off") text = "The worker is off: memory records, but learns nothing new.";
  else if (running) text = `Working now: ${w.pass === "nightly" ? "the nightly pass" : "an idle pass"}, ${STEP_WORDS[w.step] || "starting"}.`;
  else text = `Resting. ${w.pending ? `${plural(w.pending, "moment waits", "moments wait")} to be read when you are away.` : "Everything has been read."}${w.next_night_at ? ` Next night: ${whenLabel(w.next_night_at)}.` : ""}`;
  box.append(dot, el("span", null, text));
}

function renderPasses(d) {
  const box = $("mv-passes");
  const last = {};
  for (const r of d.recent || []) if (!last[r.pass]) last[r.pass] = r;
  const items = ["idle", "nightly"].filter((p) => last[p]).map((p) => last[p]);
  if (!items.length) {
    box.replaceChildren(el("p", "small muted", "No pass has run yet. The worker reads new moments after ten quiet minutes, and does more at night."));
    return;
  }
  const wrap = el("div", "mv-pass-grid");
  for (const r of items) {
    const card = el("section", "mv-pass");
    const outcome = { done: "done", partial: "partly done", failed: "failed", yielded: "stopped for the voice", skipped: "skipped" }[r.outcome] || r.outcome;
    card.append(el("h4", null, `${r.pass === "nightly" ? "Last nightly pass" : "Last idle pass"}`),
      el("p", "small muted", `${whenLabel(r.started_at)} · ${outcome} · ${r.seconds < 10 ? r.seconds.toFixed(1) : Math.round(r.seconds)} s`),
      el("p", "small", passSummary(r.counts)));
    const total = Math.max(0.001, (r.steps || []).reduce((n, x) => n + (x.seconds || 0), 0));
    const ol = el("ol", "mv-steps");
    for (const st of r.steps || []) {
      const li = el("li", st.error ? "failed" : "");
      const bar = el("span", "mv-step-bar");
      bar.style.width = `${Math.max(2, (100 * (st.seconds || 0)) / total)}%`;
      const name = STEP_WORDS[st.step] || st.step;
      li.append(el("span", "mv-step-name", `${name[0].toUpperCase()}${name.slice(1)}`), el("span", "mv-step-track"), el("span", "mv-step-s", `${(st.seconds || 0).toFixed(1)} s`));
      li.querySelector(".mv-step-track").append(bar);
      if (st.error) li.append(el("span", "mv-step-error small", st.error));
      ol.append(li);
    }
    card.append(ol);
    if (r.error && r.fix) card.append(el("p", "small muted", `Fix: ${r.fix}`));
    wrap.append(card);
  }
  box.replaceChildren(wrap);
}

function renderFlowList(d) {
  const box = $("mv-flow-list");
  const ul = el("ul", "mv-text-list");
  for (const st of flowStages(d)) {
    const li = el("li");
    li.append(el("strong", null, st.title), el("span", null, `: ${st.items.map((i) => `${i.label.toLowerCase()} ${i.n}`).join(", ")}.`));
    ul.append(li);
  }
  box.replaceChildren(ul);
}

// ------------------------------------------------------------------ loading and tabs

const PATHS = { graph: () => "/api/memory/graph", map: () => "/api/memory/map", timeline: () => `/api/memory/timeline?range=${range}`, flow: () => "/api/memory/flow" };
const RENDER = { graph: renderGraph, map: renderMap, timeline: renderTimeline, flow: renderFlow };

async function loadTab(which = tab) {
  const n = ++seq;
  const box = $(`mv-${which}-figure`);
  if (!data[which]) {
    const l = el("div", "loading-lines");
    l.setAttribute("aria-hidden", "true");
    l.append(el("i"), el("i"), el("i"));
    box.replaceChildren(l);
  }
  box.setAttribute("aria-busy", "true");
  try {
    const d = await api(PATHS[which]());
    if (n !== seq && which === tab) return;
    data[which] = d;
    box.removeAttribute("aria-busy");
    if (which === tab) RENDER[which]();
  } catch (e) {
    box.removeAttribute("aria-busy");
    if (which !== tab) return;
    status("");
    empty(box, "This picture cannot be drawn.", app.connection === "lost" ? "Marvin’s host is not answering. It will come back on its own." : e.message);
  }
}

function select(which, focus = false, fetch = true) {
  tab = which;
  local.set("marvin.memviews", which);
  for (const b of document.querySelectorAll("[data-mv-tab]")) {
    const on = b.dataset.mvTab === which;
    b.setAttribute("aria-selected", String(on));
    b.tabIndex = on ? 0 : -1;
    if (on && focus) b.focus();
  }
  for (const t of TABS) $(`mv-${t}`).hidden = t !== which;
  $("mv-intro").textContent = INTRO[which];
  if (data[which]) RENDER[which]();
  if (fetch) loadTab(which);
}

function visible() {
  return document.body.dataset.view === "memory";
}

/** Memory changed: the shown picture follows, at most once a second. */
function soon() {
  clearTimeout(reloadTimer);
  reloadTimer = setTimeout(() => { if (visible()) loadTab(); }, 1000);
}

export function setup() {
  const tabs = [...document.querySelectorAll("[data-mv-tab]")];
  for (const b of tabs) {
    b.addEventListener("click", () => select(b.dataset.mvTab));
    b.addEventListener("keydown", (ev) => {
      const i = tabs.indexOf(b);
      const j = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[ev.key];
      if (j == null) return;
      ev.preventDefault();
      select(tabs[(j + tabs.length) % tabs.length].dataset.mvTab, true);
    });
  }
  for (const b of document.querySelectorAll("[data-zoom]")) b.addEventListener("click", () => zoom(b.dataset.zoom));
  $("mv-sheet-close").addEventListener("click", () => closeSheet());
  $("mv-sheet").addEventListener("keydown", (ev) => { if (ev.key === "Escape") { ev.stopPropagation(); closeSheet(); } });
  for (const r of document.querySelectorAll('input[name="mv-range"]')) {
    r.addEventListener("change", () => { if (r.checked) { range = r.value; data.timeline = null; loadTab("timeline"); } });
  }
  for (const r of document.querySelectorAll('input[name="mv-colour"]')) {
    r.addEventListener("change", () => { if (r.checked) { colourBy = r.value; if (data.map) renderMap(); } });
  }
  on("memory", (m) => {
    if (!visible()) return;
    if (m.kind === "worker") {
      const was = data.flow && data.flow.worker.state;
      if (data.flow) {
        Object.assign(data.flow.worker, { state: m.state, pass: m.pass, step: m.step });
        if (tab === "flow") { renderNow(data.flow.worker); renderFlow(); }
      }
      if (tab === "flow" && was && was !== m.state) soon();
    } else if (m.kind === "changed") soon();
  });
  on("fact-changed", () => { if (visible()) soon(); });
  on("reconnected", () => { if (visible()) loadTab(); });
  let width = 0;
  window.addEventListener("resize", () => {
    const w = $("memory-views").getBoundingClientRect().width;
    if (!visible() || Math.abs(w - width) < 8) return;
    width = w;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { if (data[tab]) RENDER[tab](); }, 150);
  });
  select(tab, false, false);
}

export function load() {
  return loadTab();
}

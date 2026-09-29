// SPDX-License-Identifier: MIT
// Home: Marvin's face and the presence sentence, your decisions (always shown), your day, a small thing
// remembered, the break timer and breathing, recent moments. The modules can be hidden, shown and reordered
// (keyboard and touch, no dragging); the layout is kept in this browser only and never changes what Marvin
// senses or does.

import { $, app, el, api, post, on, emit, local, fmtTime, timeEl, toast, openDialog, closeDialog, canvas2d, cssVar, whenLabel } from "./core.js";
import { Face } from "./face.js";
import { DayCard } from "./daycard.js";
import { sourceLine, openFact } from "./memdata.js";

const LAYOUT_KEY = "marvin.home";
const MODULES = [
  { id: "presence", name: "Marvin, here with you" },
  { id: "day", name: "Your day" },
  { id: "memory", name: "A small thing remembered" },
  { id: "body", name: "Breaks and breathing" },
  { id: "moments", name: "Recent moments" },
];
const DEFAULT = { order: MODULES.map((m) => m.id), hidden: [] };
const QUIET_KINDS = new Set(["vitals_acquired", "vitals_lost"]);
const SYSTEM_KINDS = new Set(["host_started", "host_stopped", "robot_online", "robot_offline"]);

let layout = DEFAULT;
let todayCard;
let lastEventId = 0;
let lastStateAt = 0;
let shownDate = null;
let expiryTimer = 0;
const breaths = [];           // [t, breaths per minute], the last ten minutes, one every 5 s

// ------------------------------------------------------------------ layout

function readLayout() {
  const l = local.get(LAYOUT_KEY, null);
  if (!l || !Array.isArray(l.order)) return { ...DEFAULT };
  const known = new Set(DEFAULT.order);
  const order = l.order.filter((id) => known.has(id));
  for (const id of DEFAULT.order) if (!order.includes(id)) order.push(id);
  return { order, hidden: (l.hidden || []).filter((id) => known.has(id)) };
}

function applyLayout() {
  const grid = $("home-modules");
  const decision = $("home-decision");
  const visible = layout.order.filter((id) => !layout.hidden.includes(id));
  for (const m of MODULES) grid.querySelector(`[data-module="${m.id}"]`).hidden = layout.hidden.includes(m.id);
  const nodes = layout.order.map((id) => grid.querySelector(`[data-module="${id}"]`));
  // your decisions cannot be hidden: always second, after the first module shown (or first of all)
  const at = visible.length ? layout.order.indexOf(visible[0]) + 1 : 0;
  nodes.splice(at, 0, decision);
  grid.replaceChildren(...nodes);
  todayCard.redraw();
  drawBreaths();
}

function saveLayout() {
  local.set(LAYOUT_KEY, layout);
  applyLayout();
}

export function resetLayout() {
  layout = { order: [...DEFAULT.order], hidden: [] };
  local.set(LAYOUT_KEY, null);
  applyLayout();
}

function customize() {
  const body = openDialog("Your Home", { label: "Customize · kept in this browser" });
  body.append(el("p", null, "Show, hide and reorder what Home shows. This changes only this screen: Marvin keeps sensing and remembering as before."));
  const list = el("ol", "modules-list");
  body.append(list);
  const draw = (focusId, dir) => {
    list.replaceChildren();
    layout.order.forEach((id, i) => {
      const m = MODULES.find((x) => x.id === id);
      const li = el("li", "module-choice");
      const label = el("label", "check");
      const box = el("input");
      box.type = "checkbox";
      box.checked = !layout.hidden.includes(id);
      box.addEventListener("change", () => {
        layout.hidden = box.checked ? layout.hidden.filter((x) => x !== id) : [...layout.hidden, id];
        saveLayout();
      });
      label.append(box, el("span", null, m.name));
      const moves = el("div", "moves");
      const move = (d, icon, text) => {
        const b = el("button");
        b.type = "button";
        b.innerHTML = `<svg class="icon" aria-hidden="true"><use href="#${icon}"/></svg>`;
        b.append(el("span", "sr", `${text} ${m.name}`));
        b.dataset.dir = d;
        b.disabled = (d < 0 && i === 0) || (d > 0 && i === layout.order.length - 1);
        b.addEventListener("click", () => {
          const o = layout.order;
          [o[i], o[i + d]] = [o[i + d], o[i]];
          saveLayout();
          draw(id, d);
        });
        return b;
      };
      moves.append(move(-1, "i-up", "Move up"), move(1, "i-down", "Move down"));
      li.append(label, moves);
      list.append(li);
      if (id === focusId) {
        // keep the keyboard on the moved module
        const b = moves.querySelector(`[data-dir="${dir}"]:not(:disabled)`) || moves.querySelector("button:not(:disabled)");
        requestAnimationFrame(() => b && b.focus());
      }
    });
  };
  draw();
  body.append(el("p", "fixed-note", "Your decisions always stay on Home, whatever you hide."));
  const actions = el("div", "actions");
  const reset = el("button", null, "Reset to the suggested layout");
  reset.type = "button";
  reset.addEventListener("click", () => { resetLayout(); draw(); toast("Home layout reset."); });
  const done = el("button", "primary", "Done");
  done.type = "button";
  done.addEventListener("click", closeDialog);
  actions.append(reset, done);
  body.append(actions);
  $("home-modules").classList.add("editing");
  on("dialog-closed", () => $("home-modules").classList.remove("editing"));
}

// ------------------------------------------------------------------ presence, break, breathing

function renderState(s) {
  lastStateAt = performance.now();
  const orb = $("home-orb");
  orb.dataset.state = s.online ? (s.present ? "present" : "away") : "offline";
  // a live region: written only when the words change (the state comes twice a second)
  if ($("presence-h").textContent !== s.status) $("presence-h").textContent = s.status;
  const detail = [];
  if (s.detail) detail.push(s.detail.replace(/[.!?]?\s*$/, "."));
  if (s.simulated) detail.push("Simulated sensors.");
  const text = detail.join(" ");
  if ($("presence-detail").textContent !== text) $("presence-detail").textContent = text;
  $("presence-label").textContent = !s.online ? "Not connected" : s.present ? "Here with you" : "Waiting for you";

  const b = s.break, card = $("break");
  const pct = s.seated ? Math.round(100 * b.progress) : 0;
  $("break-fill").style.width = `${pct}%`;
  $("break-bar").setAttribute("aria-valuenow", String(pct));
  card.classList.toggle("due", b.due);
  const left = Math.max(0, b.interval_s - b.seated_s);
  const num = $("break-number");
  if (!s.online || !s.seated) num.replaceChildren("–");
  else if (b.due) num.replaceChildren("Now");
  else num.replaceChildren(String(Math.max(1, Math.ceil(left / 60))), el("small", null, " min"));
  let hint;
  if (!s.online) hint = "Marvin is not connected.";
  else if (b.due && s.quiet) hint = "Quiet hours: Marvin keeps it to himself.";
  else if (b.due) hint = "Time to stand up for a few minutes.";
  else if (s.seated) hint = `${Math.floor(b.seated_s / 60)} of ${Math.round(b.interval_s / 60)} minutes seated.`;
  else if (s.present) hint = "You’re up. The timer starts when you sit down.";
  else hint = "The timer starts when you sit down.";
  $("break-hint").textContent = hint;

  const now = Date.now() / 1000;
  if (s.breath_rate != null && (!breaths.length || now - breaths[breaths.length - 1][0] >= 5)) {
    breaths.push([now, s.breath_rate]);
    while (breaths.length && now - breaths[0][0] > 600) breaths.shift();
  }
  renderBreath(s);
}

function renderBreath(s) {
  const n = $("breath-number");
  const stale = performance.now() - lastStateAt > 10000;
  if (s && s.breath_rate != null) {
    n.replaceChildren(String(Math.round(s.breath_rate)), el("small", null, " / min"));
    const heart = s.heart_rate != null ? ` Heart ${Math.round(s.heart_rate)} / min.` : "";
    $("breath-hint").textContent = stale ? "Last reading, no longer live." : s.simulated ? `Simulated reading.${heart}` : `Measured now.${heart}`;
  } else {
    n.replaceChildren("–");
    $("breath-hint").textContent = !s || !s.online ? "Marvin is not connected." : s.vitals_sensor === false
      ? "No vital signs radar connected." : "Readable while you sit still in front of Marvin.";
  }
  $("breath").classList.toggle("stale", stale);
  drawBreaths();
}

function drawBreaths() {
  const c = $("breath-spark");
  const g = canvas2d(c);
  if (!g) return;
  const { ctx, w, h } = g;
  ctx.strokeStyle = cssVar("--line");
  ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(0, h - 0.5); ctx.lineTo(w, h - 0.5); ctx.stroke();
  if (breaths.length < 2) return;
  const now = Date.now() / 1000;
  const vals = breaths.map((p) => p[1]);
  const lo = Math.min(...vals) - 2, hi = Math.max(...vals) + 2;
  ctx.strokeStyle = cssVar("--strong");
  ctx.lineWidth = 2;
  ctx.lineJoin = "round";
  ctx.beginPath();
  breaths.forEach(([t, v], i) => {
    const x = w - ((now - t) / 600) * w, y = 3 + (1 - (v - lo) / (hi - lo)) * (h - 6);
    if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y);
  });
  ctx.stroke();
}

// ------------------------------------------------------------------ recent moments

function eventItem(e) {
  const li = el("li");
  li.dataset.id = e.id;
  const p = el("span", null, e.text);
  if (SYSTEM_KINDS.has(e.kind)) p.className = "system";
  if (e.kind === "still_long") p.className = "attention";
  li.append(timeEl(e.ts), p);
  return li;
}

async function loadEvents() {
  const r = await api("/api/events?quiet=1&limit=5");
  $("events").replaceChildren(...r.events.map(eventItem));
  lastEventId = r.events.reduce((m, e) => Math.max(m, e.id), lastEventId);
  $("events-empty").hidden = r.events.length > 0;
}

function addEvent(e) {
  if (e.id <= lastEventId) return;
  lastEventId = e.id;
  if (QUIET_KINDS.has(e.kind)) return;
  const ol = $("events");
  const li = eventItem(e);
  li.classList.add("fresh");
  ol.prepend(li);
  while (ol.children.length > 5) ol.lastElementChild.remove();
  $("events-empty").hidden = true;
}

// ------------------------------------------------------------------ memory

async function loadMemory() {
  const box = $("home-memory");
  let r;
  try {
    r = await api("/api/memory/facts?filter=all&limit=3");
  } catch (e) {
    box.replaceChildren(el("p", "source", "Memory is not available right now."));
    return;
  }
  if (!r.facts.length) {
    box.replaceChildren(el("p", "quote", "Nothing yet."),
      el("p", "source", "Marvin learns from your conversations while you are away, or at once when you say “remember that…”."));
    return;
  }
  const first = r.facts[0];
  let src = null;
  try {
    const d = await api(`/api/memory/facts/${first.id}`);
    src = d.sources[0] || null;
  } catch (e) { /* the line says less */ }
  const quote = el("p", "quote", `“${first.statement}”`);
  const line = el("p", "source", sourceLine(first, src));
  const nodes = [quote, line];
  if (r.facts.length > 1) {
    const more = el("ul", "more");
    for (const f of r.facts.slice(1)) {
      const li = el("li");
      li.append(el("span", null, f.statement), el("span", "source", whenLabel(f.learned_at)));
      more.append(li);
    }
    nodes.push(more);
  }
  const btn = el("button", "link-button small", "Read the source & correct");
  btn.type = "button";
  btn.addEventListener("click", () => openFact(first.id));
  nodes.push(btn);
  box.replaceChildren(...nodes);
}

// ------------------------------------------------------------------ your decisions

async function loadDecisions() {
  let pending = [];
  try {
    pending = (await api("/api/memory/forget")).pending;
  } catch (e) { /* none shown */ }
  // a proposal expires on its own (five minutes): look again then
  clearTimeout(expiryTimer);
  if (pending.length) {
    const next = Math.min(...pending.map((p) => p.expires_at)) * 1000 - Date.now();
    expiryTimer = setTimeout(loadDecisions, Math.max(1000, next + 1000));
  }
  const panel = $("home-decision"), body = $("decision-body"), pill = $("decision-pill");
  panel.classList.toggle("pending", pending.length > 0);
  emit("badge", { view: "home", n: pending.length, label: `${pending.length} decision${pending.length === 1 ? "" : "s"} waiting` });
  emit("decisions", pending);
  if (!pending.length) {
    pill.className = "pill";
    pill.textContent = "Nothing pending";
    const row = el("div", "row");
    row.append(el("span", "small muted", "Nothing is done without you."));
    body.replaceChildren(el("h2", null, "Nothing needs you."),
      el("p", null, "When Marvin needs your yes, for example before forgetting something you asked it to forget, it waits here. Tasks that act for you will ask here too, once Marvin can run them."),
      row);
    return;
  }
  pill.className = "pill attention-pill";
  pill.textContent = `${pending.length} pending`;
  const p = pending[0];
  const nodes = [];
  nodes.push(el("h2", null, p.everything ? "Forget everything?" : p.facts.length === 1 ? "Forget this memory?" : `Forget ${p.facts.length} memories?`));
  const how = p.origin === "voice" ? "You asked by voice" : "Asked in the app";
  const same = p.facts.length === 1 && p.query && p.query.trim().toLowerCase() === p.facts[0].statement.trim().toLowerCase();
  nodes.push(el("p", null, `${how}${p.query && !same ? `: “${p.query}”` : ""}. Waiting until ${fmtTime(p.expires_at)}.`));
  if (p.facts.length) {
    const ul = el("ul", "decision-facts");
    for (const f of p.facts.slice(0, 3)) ul.append(el("li", null, f.statement));
    nodes.push(ul);
    if (p.facts.length > 3) nodes.push(el("p", null, `and ${p.facts.length - 3} more`));
  }
  const row = el("div", "row");
  row.append(el("span", "small muted", pending.length > 1 ? `${pending.length - 1} more after this one.` : "The conversations stay; the memory goes."));
  const acts = el("div", "decision-actions");
  const keep = el("button", null, "Keep");
  keep.type = "button";
  const forget = el("button", "primary", "Forget");
  forget.type = "button";
  keep.addEventListener("click", async () => {
    try { await post("/api/memory/forget/cancel", { confirm: p.confirm }); toast("Kept. Nothing was forgotten."); } catch (e) { toast(e.message); }
    loadDecisions();
  });
  forget.addEventListener("click", async () => {
    try {
      const r = await post("/api/memory/forget/confirm", { confirm: p.confirm });
      toast(`Forgotten: ${r.forgotten.facts} memor${r.forgotten.facts === 1 ? "y" : "ies"}.`);
    } catch (e) { toast(e.message); }
    loadDecisions();
    loadMemory();
  });
  acts.append(keep, forget);
  row.append(acts);
  nodes.push(row);
  body.replaceChildren(...nodes);
}

// ------------------------------------------------------------------ setup

export function setup() {
  todayCard = new DayCard($("today"));
  layout = readLayout();
  applyLayout();
  new Face($("home-face"), { span: 330, labelled: $("home-face") });
  $("customize").addEventListener("click", customize);
  $("home-mute").addEventListener("click", async () => {
    try {
      const r = await post("/api/voice/mute", { muted: !(app.voice && app.voice.muted) });
      if (r.voice) emit("voice", r.voice);
    } catch (e) { toast(e.message); }
  });
  on("state", renderState);
  on("today", (d) => {
    todayCard.render(d);
    $("home-date").textContent = new Date(d.date + "T12:00:00").toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" });
    if (shownDate && shownDate !== d.date) loadEvents().catch(() => {});   // a new day
    shownDate = d.date;
  });
  on("event", addEvent);
  on("voice", (v) => {
    const b = $("home-mute");
    b.disabled = v.state !== "on";
    b.setAttribute("aria-pressed", String(!!v.muted));
    b.textContent = v.muted ? "Unmute" : "Mute";
    b.title = v.state !== "on" ? "The voice is off" : v.muted ? "The microphone is muted: press to unmute" : "Mute the microphone";
  });
  on("memory", (m) => {
    if (m.kind === "forget") loadDecisions();
    if (m.kind === "changed" && (m.what === "facts" || m.what === "all")) loadMemory();
  });
  on("settings", () => { if (app.today) todayCard.render(app.today); });
  on("view", ({ view }) => { if (view === "home") { todayCard.redraw(); drawBreaths(); } });
  on("resize", () => { todayCard.redraw(); drawBreaths(); });
  on("theme", () => drawBreaths());
  on("reconnected", () => { loadEvents().catch(() => {}); loadDecisions(); loadMemory(); });
  on("reset-layout", resetLayout);
  setInterval(() => { if (app.state) renderBreath(app.state); }, 5000);
}

export async function load() {
  await Promise.all([loadEvents(), loadMemory(), loadDecisions()].map((p) => p.catch(() => {})));
}


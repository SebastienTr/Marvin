// SPDX-License-Identifier: MIT
// Marvin's app, the Java host's: the shell (five destinations, a rail on a computer and a bar on a phone), the
// appearance, the connection, and the one event stream that feeds every screen. Each screen is its own module:
// home.js, talk.js, memory.js, activity.js, marvin.js. Plain ES modules, no build step, no network access
// beyond this host.

import { $, app, api, emit, on, toast } from "./core.js";
import { Face, setFaceState } from "./face.js";
import * as home from "./home.js";
import * as talk from "./talk.js";
import * as memory from "./memory.js";
import * as activity from "./activity.js";
import * as marvin from "./marvin.js";
import * as system from "./system.js";

const VIEWS = ["home", "talk", "memory", "activity", "marvin"];
const SUBS = { activity: ["", "history"], marvin: ["", "robot", "voice", "system", "preferences"] };
// earlier addresses, still honoured (bookmarks, the phone's home screen)
const OLD = { robot: "marvin/robot", history: "activity/history", settings: "marvin/preferences", "activity/log": "marvin/system" };

// ------------------------------------------------------------------ navigation

function route() {
  let h = (location.hash || "#home").slice(1);
  if (OLD[h]) {
    h = OLD[h];
    history.replaceState(null, "", `#${h}`);
  }
  let [view, sub = ""] = h.split("/");
  if (!VIEWS.includes(view)) view = "home";
  if (!(SUBS[view] || [""]).includes(sub)) sub = "";
  return { view, sub };
}

function show({ view, sub }, focus) {
  const changed = app.view !== view || app.sub !== sub;
  app.view = view;
  app.sub = sub;
  document.body.dataset.view = view;
  for (const s of document.querySelectorAll("section.view")) s.hidden = s.dataset.view !== view;
  for (const a of document.querySelectorAll(".side-nav a, .bottom-nav a")) {
    if (a.dataset.view === view) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  }
  for (const d of document.querySelectorAll(".sub")) {
    if (d.dataset.of === view) d.hidden = d.dataset.sub !== sub;
  }
  for (const a of document.querySelectorAll(`section.view[data-view="${view}"] .subnav a`)) {
    if (a.dataset.sub === sub) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  }
  if (changed) emit("view", { view, sub });
  if (focus) {
    const h1 = document.querySelector(`section.view[data-view="${view}"] h1`);
    if (h1) h1.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }
}

function setupNav() {
  let byUser = false;
  document.addEventListener("click", (ev) => {
    const a = ev.target.closest("a[href^='#']");
    if (a) byUser = true;
  });
  window.addEventListener("hashchange", () => { show(route(), byUser); byUser = false; });
  show(route(), false);
}

// ------------------------------------------------------------------ appearance

function setupTheme() {
  const buttons = [...document.querySelectorAll("[data-theme-choice]")];
  const radios = [...document.querySelectorAll('input[name="appearance"]')];
  const sync = () => {
    const c = window.marvinTheme ? window.marvinTheme.choice : "auto";
    for (const b of buttons) b.setAttribute("aria-pressed", String(b.dataset.themeChoice === c));
    for (const r of radios) r.checked = r.value === c;
    const line = $("appearance-line");
    if (line) line.textContent = { day: "Day", night: "Night", auto: "Auto: follows this device" }[c];
  };
  for (const b of buttons) b.addEventListener("click", () => window.marvinTheme.set(b.dataset.themeChoice));
  for (const r of radios) r.addEventListener("change", () => { if (r.checked) window.marvinTheme.set(r.value); });
  document.addEventListener("marvin-theme", (ev) => { sync(); emit("theme", ev.detail); });
  sync();
}

// ------------------------------------------------------------------ connection and simulated sensors

function setConnection(state) {
  app.connection = state;
  const conn = $("conn");
  conn.dataset.state = state;
  $("conn-text").textContent = {
    connecting: "Connecting", live: "Live · on this computer", lost: "Reconnecting", offline: "Robot offline",
  }[state];
  conn.title = $("conn-text").textContent;
  // every screen: say plainly when what it shows may be out of date
  const banner = $("conn-banner");
  banner.hidden = state !== "lost" || !bannerDue;
  emit("connection", state);
}

// the banner waits a few seconds: a short reconnection (the host restarting) should not flash it
let bannerDue = false;
let bannerTimer = 0;
on("connection", (state) => {
  if (state === "lost" && !bannerDue && !bannerTimer) {
    bannerTimer = setTimeout(() => { bannerTimer = 0; bannerDue = true; if (app.connection === "lost") $("conn-banner").hidden = false; }, 4000);
  } else if (state !== "lost") {
    clearTimeout(bannerTimer);
    bannerTimer = 0;
    bannerDue = false;
    $("conn-banner").hidden = true;
  }
});

function setupSimBadge() {
  const b = $("sim-badge");
  const set = (open) => { b.classList.toggle("open", open); b.setAttribute("aria-expanded", String(open)); };
  b.addEventListener("click", (ev) => { ev.stopPropagation(); set(!b.classList.contains("open")); });
  b.addEventListener("blur", () => set(false));
  b.addEventListener("keydown", (ev) => { if (ev.key === "Escape") set(false); });
  document.addEventListener("click", () => set(false));
  on("state", (s) => { b.hidden = !s.simulated; });
}

// ------------------------------------------------------------------ the stream

function connect() {
  const es = new EventSource("/api/stream");
  let opened = false;
  const json = (m) => JSON.parse(m.data);
  es.addEventListener("open", () => {
    setConnection(app.state && !app.state.online ? "offline" : "live");
    // after a reconnection (the host restarted, the computer slept): catch up on what was missed
    if (opened) emit("reconnected");
    opened = true;
  });
  es.addEventListener("error", () => setConnection("lost"));
  es.addEventListener("hello", (m) => emit("settings", json(m).settings));
  es.addEventListener("state", (m) => {
    const s = json(m);
    setState(s);
    setConnection(s.online ? "live" : "offline");
  });
  for (const name of ["today", "event", "settings", "voice", "level", "utterance", "partial", "say", "log", "devices", "memory"]) {
    es.addEventListener(name, (m) => emit(name, json(m)));
  }
  es.addEventListener("transcript", (m) => emit("transcript", { entry: json(m), fresh: true }));
}

function setState(s) {
  app.state = s;
  setFaceState({ robot: s.expression || (s.online ? (s.present ? "neutral" : "calm") : "asleep") });
  emit("state", s);
}

on("settings", (s) => { app.settings = s; });
on("voice", (v) => {
  app.voice = v;
  const on = v.state === "on";
  setFaceState({ voice: on ? v.status : v.state === "unavailable" ? "off" : v.state, muted: !!v.muted });
  for (const d of document.querySelectorAll("[data-live]")) d.hidden = !(on && (v.status === "listening" || v.status === "speaking"));
});
on("today", (d) => { app.today = d; });

/** A count on a destination (the rail and the phone's bar). */
export function badge(view, n, label) {
  for (const b of document.querySelectorAll(`[data-count="${view}"]`)) {
    b.hidden = !n;
    b.textContent = n ? String(n) : "";
    if (label) b.setAttribute("aria-label", label);
  }
}
on("badge", ({ view, n, label }) => badge(view, n, label));

// ------------------------------------------------------------------ dialog

function setupDialog() {
  const d = $("dialog");
  $("dialog-close").addEventListener("click", () => d.close());
  d.addEventListener("click", (ev) => { if (ev.target === d) d.close(); });   // a click on the backdrop
  d.addEventListener("close", () => emit("dialog-closed"));
}

// ------------------------------------------------------------------ start

async function init() {
  setupTheme();
  setupDialog();
  setupSimBadge();
  new Face($("brand-eyes"), { span: 250, still: true });
  home.setup();
  talk.setup();
  memory.setup();
  activity.setup();
  marvin.setup();
  system.setup();
  try {
    const r = await api("/api/state");
    app.settings = r.settings;
    emit("settings", r.settings);
    setState(r.state);
    app.today = r.today;
    emit("today", r.today);
  } catch (e) {
    setConnection("lost");
    toast("Cannot reach Marvin’s host.");
  }
  setupNav();
  await Promise.all([home.load(), talk.load(), memory.load(), activity.load()].map((p) => p.catch(() => {})));
  connect();
  let resizeTimer;
  window.addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => emit("resize"), 150);
  });
}

init();

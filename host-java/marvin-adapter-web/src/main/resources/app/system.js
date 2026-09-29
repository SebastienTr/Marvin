// SPDX-License-Identifier: MIT
// Marvin > System: the services that make Marvin (database, robot link, memory, voice sidecar, Ollama), each with
// its state and, when it is down, why and how to fix it; a restart for the voice sidecar, the one the host can
// restart; this computer's host; and the log, filtered and searchable, live.

import { $, app, el, api, post, on, emit, timeEl, toast } from "./core.js";
import * as worker from "./worker.js";

const NAMES = { database: "Database", robot: "Robot link", memory: "Memory", voice: "Voice sidecar", ollama: "Ollama" };
const STATE_WORDS = { up: "Running", down: "Down", degraded: "Limited", off: "Off", starting: "Starting", unavailable: "Unavailable" };

let health = null;
let options = null;

function service(name, state, detail, fix, action) {
  const li = el("li", "service");
  const head = el("div", "service-head");
  head.append(el("b", null, NAMES[name] || name), el("span", `state-pill ${state}`, STATE_WORDS[state] || state));
  li.append(head);
  if (detail) li.append(el("p", "small muted", detail));
  if (fix) { const f = el("p", "notice-fix small"); f.append(el("span", "muted", "Fix: "), el("code", null, fix)); li.append(f); }
  if (action) li.append(action);
  return li;
}

function voiceState() {
  const v = app.voice;
  if (!v) return ["unavailable", "No word from the voice yet.", ""];
  if (v.state === "on") return ["up", `Listening and speaking${v.model ? ` · ${v.model}` : ""}${v.muted ? " · microphone muted" : ""}`, ""];
  if (v.state === "starting") return ["starting", "Starting: loading speech recognition and the voice.", ""];
  if (v.state === "stopping") return ["starting", "Stopping.", ""];
  if (v.state === "error") return ["down", v.error || "The voice stopped.", v.fix || ""];
  if (v.state === "unavailable") return ["unavailable", v.error || "Not available on this host.", v.fix || ""];
  return ["off", "Switched off. Turn it on in Talk.", ""];
}

function restartButton() {
  const v = app.voice;
  if (!v || v.state === "unavailable") return null;
  const b = el("button", "small-button gap-top", v.state === "on" || v.state === "error" ? "Restart the voice" : "Start the voice");
  b.type = "button";
  b.disabled = v.state === "starting" || v.state === "stopping";
  b.addEventListener("click", async () => {
    b.disabled = true;
    try {
      if (v.state === "on" || v.state === "error") {
        await post("/api/voice/off", {});
        await new Promise((r) => setTimeout(r, 400));
      }
      const r = await post("/api/voice/on", {});
      if (r.voice) emit("voice", r.voice);
      toast("The voice is starting again.");
    } catch (e) {
      toast(`Cannot restart: ${e.message}`);
      b.disabled = false;
    }
  });
  return b;
}

function render() {
  const ul = $("services");
  if (!health) {
    ul.replaceChildren(el("li", "small muted", app.connection === "lost" ? "Marvin’s host is not answering." : "Loading…"));
    return;
  }
  const items = [];
  const c = health.components || {};
  for (const name of ["database", "robot", "memory"]) {
    if (!c[name]) continue;
    let fix = "";
    if (name === "memory") { const w = worker.current(); const p = w && w.embeddings && w.embeddings.state === "unavailable" ? w.embeddings : null; fix = p ? p.fix : ""; }
    items.push(service(name, c[name].state, c[name].detail, fix));
  }
  const [vs, vd, vf] = voiceState();
  items.push(service("voice", vs, vd + (c.voice && c.voice.detail && vs === "up" ? ` · ${c.voice.detail}` : ""), vf, restartButton()));
  if (options && options.ollama) {
    const o = options.ollama;
    items.push(service("ollama", o.ok ? "up" : "down", o.ok ? `${options.llm_models.length} models installed${o.host ? ` · ${o.host}` : ""}` : o.error, o.ok ? "" : o.fix));
  }
  ul.replaceChildren(...items);
  const down = items.filter((li) => li.querySelector(".state-pill.down")).length;
  $("services-summary").textContent = down ? `${down} need${down === 1 ? "s" : ""} attention` : "all well";
  const lines = $("host-lines");
  const line = (a, b) => { const li = el("li"); li.append(el("span", null, a), el("span", null, b)); return li; };
  lines.replaceChildren(line("Version", health.version || "–"), line("Mode", health.mode === "demo" ? "demo: a simulated robot and past week" : health.mode || "–"));
  $("host-mode").textContent = health.status === "ok" ? "Healthy" : health.status || "–";
  $("host-mode").className = `pill${health.status === "ok" ? "" : " attention-pill"}`;
}

async function load() {
  try { health = await api("/api/health"); } catch (e) { health = health || null; }
  render();
  api("/api/voice/options").then((o) => { options = o; render(); }).catch(() => {});
  api("/api/settings").then((r) => {
    if (r.about && r.about.data_dir) {
      const li = el("li");
      li.append(el("span", null, "Data"), el("code", null, r.about.data_dir));
      $("host-lines").append(li);
    }
  }).catch(() => {});
  worker.load().then(render);
}

// ------------------------------------------------------------------ log

const SOURCE_LABEL = { brain: "Marvin", device: "Device", host: "Host", voice: "Voice" };
let log = [];

function logFilter() {
  const v = document.querySelector('input[name="log-filter"]:checked').value;
  const q = $("log-q").value.trim().toLowerCase();
  return (e) => (!v || (v === "warning" ? (e.level === "warning" || e.level === "error")
    : v === "brain" ? (e.source === "brain" || e.source === "host") : e.source === v))
    && (!q || e.text.toLowerCase().includes(q) || (e.device || "").toLowerCase().includes(q));
}

function logItem(e) {
  const li = el("li");
  const dev = e.source === "device" && e.device;
  const text = el("span", `text ${e.level === "attention" ? "attention" : e.level === "warning" || e.level === "error" ? e.level : ""}`, e.text);
  li.append(timeEl(e.ts), el("span", dev ? "src dev" : "src", dev ? e.device : SOURCE_LABEL[e.source] || e.source), text);
  return li;
}

function renderLog() {
  const items = log.filter(logFilter()).slice(0, 200);
  $("log").replaceChildren(...items.map(logItem));
  $("log-empty").hidden = items.length > 0;
  $("log-empty").textContent = log.length ? "Nothing matches." : "Nothing to show.";
}

async function loadLog() {
  try {
    log = (await api("/api/log?limit=300")).entries;
    renderLog();
  } catch (e) {
    if (!log.length) { $("log-empty").hidden = false; $("log-empty").textContent = `The log cannot be read: ${e.message}`; }
  }
}

function addLog(e) {
  if (log.length && log[0].id >= e.id) return;
  log.unshift(e);
  if (log.length > 500) log.pop();
  if (app.view !== "marvin" || app.sub !== "system" || !logFilter()(e)) return;
  const li = logItem(e);
  li.classList.add("fresh");
  $("log").prepend(li);
  while ($("log").children.length > 200) $("log").lastElementChild.remove();
  $("log-empty").hidden = true;
}

export function setup() {
  for (const r of document.querySelectorAll('input[name="log-filter"]')) r.addEventListener("change", renderLog);
  let t = 0;
  $("log-q").addEventListener("input", () => { clearTimeout(t); t = setTimeout(renderLog, 150); });
  on("log", addLog);
  on("voice", () => { if (app.view === "marvin" && app.sub === "system") render(); });
  on("view", ({ view, sub }) => { if (view === "marvin" && sub === "system") { load(); loadLog(); } });
  on("reconnected", () => { if (app.view === "marvin" && app.sub === "system") { load(); loadLog(); } });
  loadLog();
}

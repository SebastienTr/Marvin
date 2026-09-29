// SPDX-License-Identifier: MIT
// Marvin: the companion's capabilities and setup. An overview (robot and voice, Soul and connections as honest
// "later" entries, preferences), the robot (devices, the robot's own screen, lidar top view, radar, vital
// signs), the voice's settings, and the preferences (breaks, quiet hours, clock, sounds, appearance, Home layout).

import { $, app, el, api, post, on, emit, cssVar, canvas2d, toast, openDialog } from "./core.js";
import { earcons } from "./talk.js";

let devices = [];
let scene = null;
let voiceOptions = null;

// ------------------------------------------------------------------ overview

function renderOverview() {
  const s = app.state;
  const pill = $("device-pill"), line = $("device-line");
  const robots = devices.filter((d) => d.online);
  if (!s || !s.online) {
    pill.textContent = "Not connected";
    line.textContent = devices.length ? "The robot stopped answering. It reconnects on its own when it is back on the network." : "No robot has said hello yet. Power it on, on the same network as this computer.";
  } else {
    pill.textContent = s.simulated ? "Simulated device" : "Connected";
    const names = robots.map((d) => d.label).join(", ");
    line.textContent = s.simulated
      ? `${names || "A device"} says its sensor data is simulated: the person, the room and the vital signs come from a simulated scene.`
      : `${names || "The robot"} is connected.`;
  }
  const v = app.voice;
  $("system-voice-line").textContent = !v ? "–" : v.state === "on" ? `On · ${v.model}${v.muted ? " · microphone muted" : ""}`
    : v.state === "error" ? `Unavailable: ${v.error}` : v.state === "unavailable" ? "Not available on this host" : v.state === "starting" ? "Starting…" : "Off";
  const vs = app.voiceSettings;
  $("internet-line").textContent = !vs ? "–" : vs.tools === false ? "Tools off: Marvin stays offline" : vs.internet === false ? "Off: Marvin stays fully offline" : "On: the weather asks Open-Meteo, with the place name only";
  $("break-line").textContent = `Every ${app.settings.break_interval_min || 50} minutes seated${app.settings.quiet_hours && app.settings.quiet_hours.enabled ? `, quiet ${app.settings.quiet_hours.start}–${app.settings.quiet_hours.end}` : ""}`;
}

async function openSystem() {
  const body = openDialog("System", { label: "Marvin · this computer" });
  body.append(el("p", null, "Loading…"));
  try {
    const h = await api("/api/health");
    const ul = el("ul", "health-list");
    for (const [name, c] of Object.entries(h.components || {})) {
      const li = el("li");
      const head = el("span", null, name[0].toUpperCase() + name.slice(1));
      const txt = el("div");
      txt.append(el("span", `state${c.state === "up" ? "" : " down"}`, c.state), document.createElement("br"), el("span", null, c.detail || ""));
      li.append(head, txt);
      ul.append(li);
    }
    body.replaceChildren(el("p", null, `Marvin’s host ${h.version}, ${h.mode === "demo" ? "demo mode: a simulated robot and a simulated past week" : "running"}. Everything below runs on this computer.`), ul);
  } catch (e) {
    body.replaceChildren(el("p", null, `The host’s health cannot be read: ${e.message}`));
  }
}

// ------------------------------------------------------------------ robot: devices

function ago(s) {
  if (s < 1.5) return "live";
  if (s < 90) return `${Math.round(s)} s ago`;
  return `${Math.round(s / 60)} min ago`;
}

function uptime(s) {
  if (s == null) return "";
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return h ? `up ${h} h ${String(m).padStart(2, "0")}` : `up ${m} min`;
}

function metric(label, value, unit, warn) {
  const d = el("div", warn ? "warn" : "");
  const dd = el("dd", null, value);
  if (unit) dd.append(el("small", null, unit));
  d.append(el("dt", null, label), dd);
  return d;
}

const fmtRate = (x) => (x == null ? "–" : x >= 10 ? String(Math.round(x)) : x.toFixed(1));

function deviceItem(d) {
  const li = el("li", "device");
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
  const sub = [d.board, d.firmware && `firmware ${d.firmware}`, d.ip, uptime(d.uptime_s)].filter(Boolean);
  li.append(el("p", "device-sub", sub.join(" · ")));
  const tags = el("div", "tags");
  if (d.simulated) tags.append(el("span", "tag", "Simulated data"));
  if (d.camera) tags.append(el("span", "tag", "Camera"));
  if (d.audio) tags.append(el("span", "tag", "Speaker and mic"));
  if (tags.children.length) li.append(tags);
  const r = d.rates || {};
  const m = el("dl", "metrics");
  m.append(metric("Datagrams", fmtRate(r.datagrams), "/s"));
  m.append(metric("Loss", d.loss_pct == null ? "–" : d.loss_pct.toFixed(1), "%", d.loss_pct != null && d.loss_pct >= 2));
  m.append(metric("CRC errors", String(d.crc_errors), "", false));
  if (d.role !== "vitals" || r.scans) m.append(metric("Lidar", fmtRate(r.scans), `/s · ${d.points} pts`));
  if (d.role !== "vitals" || r.radar) m.append(metric("Radar", fmtRate(r.radar), "/s"));
  if (d.role === "vitals" || r.vitals) m.append(metric("Vital signs", fmtRate(r.vitals), "/s"));
  if (d.shed) m.append(metric("Dropped by host", String(d.shed), "", true));
  li.append(m);
  return li;
}

function renderDevices(list) {
  devices = list;
  $("devices-empty").hidden = list.length > 0;
  $("devices").replaceChildren(...list.map(deviceItem));
  const online = list.filter((d) => d.online).length;
  $("devices-summary").textContent = list.length ? `${online} of ${list.length} connected` : "";
  renderOverview();
}

// ------------------------------------------------------------------ robot: sensor views

function palette() {
  return {
    text: cssVar("--ink"), muted: cssVar("--muted"), seated: cssVar("--seated"), accent: cssVar("--reminder"),
    line: cssVar("--line"), fov: cssVar("--soft"),
  };
}

function rings(ctx, cx, cy, scale, meters, from, to, col) {
  ctx.lineWidth = 1;
  ctx.strokeStyle = col.line;
  for (let k = 1; k <= meters; k++) {
    ctx.beginPath();
    ctx.arc(cx, cy, k * 100 * scale, from, to);
    ctx.stroke();
  }
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

function drawLidar() {
  const g = canvas2d($("lidar"));
  if (!g) return;
  const { ctx, w, h } = g, col = palette();
  const cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2 - 8;
  const lidar = scene && scene.lidar;
  const ranges = lidar ? lidar.ranges_cm : [];
  const nz = ranges.filter((d) => d > 0).sort((a, b) => a - b);
  const p95 = nz.length ? nz[Math.floor(nz.length * 0.95)] : 300;
  const meters = Math.max(2, Math.min(8, Math.ceil(p95 / 100)));
  const scale = R / (meters * 100);
  const fov = ((scene && scene.radar) || { half_angle_deg: 60 }).half_angle_deg * Math.PI / 180;
  ctx.fillStyle = col.fov;
  ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, R, -Math.PI / 2 - fov, -Math.PI / 2 + fov); ctx.closePath(); ctx.fill();
  rings(ctx, cx, cy, scale, meters, 0, 2 * Math.PI, col);
  ctx.fillStyle = col.muted;
  ctx.font = "11px system-ui, sans-serif";
  ctx.textAlign = "center";
  for (let k = 1; k <= meters; k++) if (k === meters || k % 2 === 0 || meters <= 4) ctx.fillText(`${k} m`, cx + k * 100 * scale * 0.7071 + 12, cy + k * 100 * scale * 0.7071 + 2);
  ctx.fillStyle = col.seated;
  ctx.globalAlpha = lidar && lidar.age_s > 2 ? 0.35 : 0.9;
  for (let i = 0; i < ranges.length; i++) {
    const d = ranges[i];
    if (!d) continue;
    const a = ((i + 0.5) * 360 / ranges.length) * Math.PI / 180;
    const rr = Math.min(R + 4, d * scale);
    ctx.fillRect(cx + Math.sin(a) * rr - 1.2, cy - Math.cos(a) * rr - 1.2, 2.4, 2.4);
  }
  ctx.globalAlpha = 1;
  const toXY = (r, f) => [cx + r * scale, cy - f * scale];
  if (scene) drawTrail(ctx, scene.trail, toXY, col);
  if (scene) for (const [r, f] of scene.targets) drawPerson(ctx, ...toXY(r, f), col);
  ctx.fillStyle = col.text;
  ctx.beginPath(); ctx.moveTo(cx, cy - 9); ctx.lineTo(cx + 6.5, cy + 6); ctx.lineTo(cx - 6.5, cy + 6); ctx.closePath(); ctx.fill();
  $("lidar-meta").textContent = lidar ? `${lidar.points} points · ${meters} m${lidar.age_s > 2 ? ` · ${Math.round(lidar.age_s)} s old` : ""}` : "no scan yet";
}

function drawRadar() {
  const g = canvas2d($("radar"));
  if (!g) return;
  const { ctx, w, h } = g, col = palette();
  const fovDeg = ((scene && scene.radar) || { half_angle_deg: 60 }).half_angle_deg;
  const fov = fovDeg * Math.PI / 180;
  const targets = scene ? scene.targets : [];
  const far = Math.max(0, ...targets.map(([r, f]) => Math.hypot(r, f)), ...(scene ? scene.trail.map(([, r, f]) => Math.hypot(r, f)) : [0]));
  const meters = Math.max(3, Math.min(6, Math.ceil(far / 100 + 0.5)));
  const cx = w / 2, cy = h - 14;
  const scale = Math.min((h - 30) / (meters * 100), (w / 2 - 12) / (Math.sin(fov) * meters * 100));
  const a0 = -Math.PI / 2 - fov, a1 = -Math.PI / 2 + fov;
  ctx.fillStyle = col.fov;
  ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, meters * 100 * scale, a0, a1); ctx.closePath(); ctx.fill();
  rings(ctx, cx, cy, scale, meters, a0, a1, col);
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
  if (scene) drawTrail(ctx, scene.trail, toXY, col);
  for (const [r, f] of targets) drawPerson(ctx, ...toXY(r, f), col);
  ctx.fillStyle = col.text;
  ctx.fillRect(cx - 9, cy + 2, 18, 4);
  if (targets.length) {
    const d = Math.min(...targets.map(([r, f]) => Math.hypot(r, f))) / 100;
    $("radar-meta").textContent = `${targets.length} ${targets.length === 1 ? "person" : "people"} · ${d.toFixed(2)} m`;
  } else {
    $("radar-meta").textContent = scene ? "nobody in view" : "no frame yet";
  }
}

function drawSpark(c, series, lo, hi) {
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

function drawWave(c, waves, k) {
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

let vitalsHistory = [];
function drawVitals() {
  const v = scene && scene.vitals;
  if (v && v.rates) vitalsHistory = v.rates;
  const set = (id, val) => {
    const e = $(id);
    e.replaceChildren();
    if (val == null) { e.textContent = "–"; return; }
    e.append(String(Math.round(val)), el("small", null, " /min"));
  };
  set("vit-breath", v ? v.breath_rate : null);
  set("vit-heart", v ? v.heart_rate : null);
  $("vit-meta").textContent = !v ? "no radar yet" : v.valid ? (v.distance_cm ? `measured at ${(v.distance_cm / 100).toFixed(2)} m` : "measured") : "not readable now";
  drawSpark($("spark-breath"), vitalsHistory.map((p) => [p[0], p[1]]), 10, 18);
  drawSpark($("spark-heart"), vitalsHistory.map((p) => [p[0], p[2]]), 55, 80);
  drawWave($("wave-breath"), v ? v.waves : [], 1);
  drawWave($("wave-heart"), v ? v.waves : [], 2);
}

function drawScene() {
  drawLidar();
  drawRadar();
  drawVitals();
}

/** The robot's own stream and screen, only while its page is on screen. */
const robotView = {
  es: null,
  frame: 0,
  faceTimer: 0,
  update() {
    const want = app.view === "marvin" && app.sub === "robot" && !document.hidden;
    if (want && !this.es) {
      this.es = new EventSource("/api/robot/stream");
      this.es.addEventListener("scene", (m) => {
        scene = JSON.parse(m.data);
        if (!this.frame) this.frame = requestAnimationFrame(() => { this.frame = 0; drawScene(); });
      });
      this.es.addEventListener("devices", (m) => renderDevices(JSON.parse(m.data)));
      api("/api/robot").then((r) => { renderDevices(r.devices); scene = r.scene; drawScene(); }).catch(() => {});
      this.faceLoop();
    } else if (!want && this.es) {
      this.es.close();
      this.es = null;
      clearTimeout(this.faceTimer);
    }
  },
  // the robot's screen as the host draws it (/face.png), a few frames a second
  faceLoop() {
    clearTimeout(this.faceTimer);
    if (!this.es) return;
    const img = $("face");
    const next = new Image();
    next.onload = () => { img.src = next.src; this.faceTimer = setTimeout(() => this.faceLoop(), 250); };
    next.onerror = () => { this.faceTimer = setTimeout(() => this.faceLoop(), 3000); };
    next.src = `/face.png?t=${Date.now()}`;
  },
};

// ------------------------------------------------------------------ preferences

function flash(id, text) {
  const e = $(id);
  e.textContent = text;
  clearTimeout(e._t);
  e._t = setTimeout(() => { e.textContent = ""; }, 4000);
}

function syncQuiet() {
  $("f-quiet-start").disabled = $("f-quiet-end").disabled = !$("f-quiet").checked;
}

async function loadSettings() {
  try {
    const r = await api("/api/settings");
    const s = r.settings, form = $("settings-form");
    $("f-break").value = s.break_interval_min;
    $("f-quiet").checked = s.quiet_hours.enabled;
    $("f-quiet-start").value = s.quiet_hours.start;
    $("f-quiet-end").value = s.quiet_hours.end;
    form.querySelector(`input[name="clock"][value="${s.clock}"]`).checked = true;
    $("f-sounds").checked = s.ui_sounds !== false;
    syncQuiet();
    const about = $("about-data");
    about.replaceChildren();
    if (r.about && r.about.data_dir) {
      about.append("History, memory and settings are stored on this computer only, in ", el("code", null, r.about.data_dir),
        ". The voice’s settings are in voice.json. Memory can be exported or forgotten from the Memory screen.");
    } else {
      about.textContent = "This is a demo: nothing is kept.";
    }
  } catch (e) { /* the form stays as it is */ }
}

function setupSettings() {
  $("f-quiet").addEventListener("change", syncQuiet);
  $("f-sounds").addEventListener("change", async (ev) => {
    const onOff = ev.target.checked;
    try {
      const r = await post("/api/settings", { ui_sounds: onOff });
      emit("settings", r.settings);
      if (onOff) { earcons.unlock(); setTimeout(() => earcons.play("open"), 60); }
      flash("settings-saved", onOff ? "Sounds on" : "Sounds off");
    } catch (e) {
      ev.target.checked = !onOff;
    }
  });
  $("settings-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const err = $("settings-error");
    err.hidden = true;
    const form = ev.target;
    const body = {
      break_interval_min: Number($("f-break").value),
      quiet_hours: { enabled: $("f-quiet").checked, start: $("f-quiet-start").value || "22:00", end: $("f-quiet-end").value || "07:00" },
      clock: form.querySelector('input[name="clock"]:checked').value,
      ui_sounds: $("f-sounds").checked,
    };
    try {
      const r = await post("/api/settings", body);
      emit("settings", r.settings);
      flash("settings-saved", "Saved");
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
  const reset = () => { emit("reset-layout"); flash("layout-saved", "Home layout reset"); toast("Home layout reset."); };
  $("reset-layout").addEventListener("click", reset);
  $("reset-layout-2").addEventListener("click", reset);
}

// ------------------------------------------------------------------ voice settings

const STT_LABELS = { auto: "Auto", mlx: "MLX (Apple GPU)", "faster-whisper": "faster-whisper (CPU)" };
const TTS_LABELS = { auto: "Auto (best available)", say: "macOS say", piper: "Piper", espeak: "espeak-ng" };

function fillSelect(sel, options, value) {
  sel.replaceChildren();
  for (const o of options) {
    const opt = el("option", null, o.label);
    opt.value = o.value;
    if (o.disabled) opt.disabled = true;
    sel.append(opt);
  }
  if (value != null && ![...sel.options].some((o) => o.value === value)) {
    const opt = el("option", null, value);
    opt.value = value;
    sel.append(opt);
  }
  sel.value = value == null ? "" : value;
}

function fillVoices() {
  const tts = $("v-tts").value;
  const cur = app.voiceSettings ? app.voiceSettings.tts_voice : null;
  const opts = [{ value: "", label: "Default" }];
  const o = voiceOptions || { voices: { piper: [], say: [] }, tts_backends: [] };
  const piperOk = (o.tts_backends.find((b) => b.name === "piper") || {}).available;
  if (tts === "piper" || (tts === "auto" && piperOk)) for (const v of o.voices.piper) opts.push({ value: v.name, label: v.installed ? v.name : `${v.name} (downloads once)` });
  if (tts === "say" || (tts === "auto" && !piperOk)) for (const v of o.voices.say) opts.push({ value: v.name, label: `${v.name} (${v.locale})` });
  fillSelect($("v-voice"), opts, cur || "");
}

function fillVoiceForm(s) {
  const o = voiceOptions;
  const form = $("voice-form");
  const available = !!s;
  $("voice-fields").disabled = !available;
  form.querySelector('button[type="submit"]').disabled = !available;
  if (!available) {
    $("voice-form-note").textContent = "The voice’s settings are available when the voice can run on this host.";
    return;
  }
  $("v-llm").value = s.llm_model;
  $("v-llm-list").replaceChildren(...(o ? o.llm_models : []).map((n) => { const opt = el("option"); opt.value = n; return opt; }));
  const hint = $("v-llm-hint");
  if (!o) hint.textContent = "Models installed in Ollama.";
  else if (!o.ollama.ok) hint.textContent = `${o.ollama.error}. ${o.ollama.fix}`;
  else if (o.llm_models.length && !o.llm_models.includes(s.llm_model) && !o.llm_models.includes(`${s.llm_model}:latest`)) hint.textContent = `Not installed yet: ollama pull ${s.llm_model}`;
  else hint.textContent = `${o.llm_models.length} model${o.llm_models.length === 1 ? "" : "s"} installed in Ollama.`;
  fillSelect($("v-stt"), (o ? o.stt_backends : ["auto", "mlx", "faster-whisper"]).map((b) => ({ value: b, label: STT_LABELS[b] || b })), s.stt);
  fillSelect($("v-stt-model"), (o ? o.stt_models : ["auto"]).map((m) => ({ value: m === "auto" ? "" : m, label: m === "auto" ? "Default" : m })), s.stt_model || "");
  fillSelect($("v-tts"), (o ? o.tts_backends : [{ name: "auto", available: true }]).map((b) => ({ value: b.name, label: (TTS_LABELS[b.name] || b.name) + (b.available ? "" : " (not installed)"), disabled: !b.available && b.name !== s.tts })), s.tts);
  fillVoices();
  form.querySelector(`input[name="v-lang"][value="${s.language || "auto"}"]`).checked = true;
  $("v-wake").checked = s.wake;
  $("v-follow").value = s.follow_up_s;
  $("v-reminders").checked = s.reminders;
  $("v-tools").checked = s.tools !== false;
  $("v-internet").checked = s.internet !== false;
  $("v-home").value = s.home_place || "";
  renderToolList();
}

function renderToolList() {
  const list = $("v-tool-list");
  const tools = (voiceOptions && voiceOptions.tools) || [];
  const onOff = $("v-tools").checked, internet = $("v-internet").checked;
  $("v-internet").disabled = !onOff;
  list.hidden = !tools.length;
  list.replaceChildren(...tools.map((t) => {
    const active = onOff && (internet || !t.online);
    const li = el("li", active ? "on" : "off");
    const state = !onOff ? "Off" : active ? "On" : "Off: needs the internet";
    li.append(el("code", null, t.name), el("span", `state${active ? " on" : ""}`, state),
      el("span", "desc", t.description + (t.online ? " Online." : " Works offline.")));
    return li;
  }));
}

async function loadVoiceForm() {
  try {
    const v = await api("/api/voice");
    app.voiceSettings = v.settings;
    fillVoiceForm(v.settings);
    if (v.settings) {
      voiceOptions = await api("/api/voice/options");
      fillVoiceForm(app.voiceSettings);
    }
  } catch (e) { /* the form stays as it is */ }
}

function setupVoiceForm() {
  $("v-tts").addEventListener("change", fillVoices);
  $("v-tools").addEventListener("change", renderToolList);
  $("v-internet").addEventListener("change", renderToolList);
  $("voice-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const err = $("voice-error");
    err.hidden = true;
    const form = ev.target;
    const body = {
      llm_model: $("v-llm").value.trim(),
      stt: $("v-stt").value,
      stt_model: $("v-stt-model").value || "auto",
      tts: $("v-tts").value,
      tts_voice: $("v-voice").value,
      language: form.querySelector('input[name="v-lang"]:checked').value,
      wake: $("v-wake").checked,
      follow_up_s: Number($("v-follow").value),
      reminders: $("v-reminders").checked,
      tools: $("v-tools").checked,
      internet: $("v-internet").checked,
      home_place: $("v-home").value.trim(),
    };
    try {
      const r = await post("/api/voice/settings", body);
      app.voiceSettings = r.settings;
      emit("voice", r.voice);
      const running = r.voice.state === "on" || r.voice.state === "starting";
      flash("voice-saved", running ? "Saved. Marvin’s voice restarts." : "Saved");
      renderOverview();
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
}

// ------------------------------------------------------------------ setup

export function setup() {
  setupSettings();
  setupVoiceForm();
  $("open-system").addEventListener("click", openSystem);
  on("state", renderOverview);
  on("voice", renderOverview);
  on("settings", renderOverview);
  on("devices", (list) => { if (!robotView.es) renderDevices(list); });
  on("view", ({ view, sub }) => {
    robotView.update();
    if (view !== "marvin") return;
    if (sub === "preferences") loadSettings();
    if (sub === "voice") loadVoiceForm();
    if (sub === "" || sub === "voice") api("/api/voice").then((v) => { app.voiceSettings = v.settings; renderOverview(); }).catch(() => {});
    if (sub === "robot") requestAnimationFrame(drawScene);
  });
  on("theme", () => { if (app.view === "marvin" && app.sub === "robot") drawScene(); });
  on("resize", () => { if (app.view === "marvin" && app.sub === "robot") drawScene(); });
  document.addEventListener("visibilitychange", () => robotView.update());
  api("/api/robot").then((r) => renderDevices(r.devices)).catch(() => {});
}

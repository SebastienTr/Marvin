// SPDX-License-Identifier: MIT
// Marvin: the companion's capabilities and setup. An overview (robot and voice, the first-run setup, Soul and
// connections as honest "later" entries, preferences), the robot (devices with their link and firmware, the robot's
// own screen, radar, vital signs, and a lidar's top view when one is linked; offline and stale shown as such), voice and
// models (the voice's settings, the camera (camera.js), Ollama, memory's models), and the preferences (you: name,
// languages and greetings; breaks, quiet hours, clock, sounds, appearance, Home layout, language, tools and home
// place). System is system.js.

import { $, app, el, api, post, on, emit, toast } from "./core.js";
import { earcons } from "./talk.js";
import { deviceItem, drawLidar as drawLidarView, drawRadar as drawRadarView, drawSpark, drawWave, fmtDistance, radarLive, lidarLinked } from "./sensorviews.js";
import { languageChips, chosenLanguages, status as setupStatus } from "./setup.js";
import * as camera from "./camera.js";
import * as ears from "./ears.js";

let devices = [];
let scene = null;
let voiceOptions = null;

// ------------------------------------------------------------------ overview

function renderOverview() {
  const s = app.state;
  const pill = $("device-pill"), line = $("device-line");
  const robots = devices.filter((d) => d.online);
  if (s && !s.online && robots.length) {
    // devices that sense no presence (a lidar alone, the USB sensor bridge): live, but nobody can be seen
    pill.textContent = "Sensors only";
    line.textContent = `${robots.map((d) => d.label).join(", ")} connected, without a presence sensor. Presence needs a radar (the robot's LD2450, or the MR60BHA2 kit).`;
  } else if (!s || !s.online) {
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
  const st = setupStatus();
  const ready = st ? st.steps.filter((x) => x.state !== "missing").length : null;
  $("setup-line").textContent = !st ? "The first-run checks, any time" : st.done ? "Done: everything is ready or skipped"
    : `${ready} of ${st.steps.length} steps ready or skipped`;
  $("break-line").textContent = `Every ${app.settings.break_interval_min || 50} minutes seated${app.settings.quiet_hours && app.settings.quiet_hours.enabled ? `, quiet ${app.settings.quiet_hours.start}–${app.settings.quiet_hours.end}` : ""}`;
}

// ------------------------------------------------------------------ robot: devices

function renderDevices(list) {
  devices = list;
  $("devices-empty").hidden = list.length > 0;
  $("devices").replaceChildren(...list.map(deviceItem));
  const online = list.filter((d) => d.online).length;
  $("devices-summary").textContent = list.length ? `${online} of ${list.length} connected` : "";
  renderOverview();
  renderRobotNotice();
}

function renderRobotNotice() {
  const n = $("robot-notice");
  const s = app.state;
  if (app.connection === "lost") {
    n.hidden = false;
    $("robot-notice-title").textContent = "Marvin’s host is not answering.";
    $("robot-notice-text").textContent = "The views below are frozen at the last data received.";
  } else if (devices.length && !devices.some((d) => d.online)) {
    const lastSeen = Math.min(...devices.map((d) => d.age_s || 0));
    n.hidden = false;
    $("robot-notice-title").textContent = "The robot is offline.";
    $("robot-notice-text").textContent = `Last heard ${lastSeen < 90 ? `${Math.round(lastSeen)} s` : `${Math.round(lastSeen / 60)} min`} ago. The views show the last data it sent; it reconnects on its own.`;
  } else if (s && s.simulated) {
    n.hidden = false;
    $("robot-notice-title").textContent = "Simulated sensors.";
    $("robot-notice-text").textContent = "The robot says its data comes from a simulated scene: the person, the room and the vital signs are not real measurements.";
  } else n.hidden = true;
}

// ------------------------------------------------------------------ robot: sensor views (drawn by sensorviews.js)

function drawLidar() {
  const linked = lidarLinked(devices, scene);
  $("lidar-panel").hidden = !linked;
  if (!linked) return;
  const { range, stats } = drawLidarView($("lidar"), scene, { radar: radarLive(devices) });
  const lidar = scene && scene.lidar;
  $("lidar-meta").textContent = !lidar ? "no scan yet"
    : `${lidar.points} points · ${fmtDistance(range)} view${stats && stats.nearest_cm ? ` · nearest ${fmtDistance(stats.nearest_cm / 100)}` : ""}${lidar.age_s > 2 ? ` · ${Math.round(lidar.age_s)} s old` : ""}`;
}

function drawRadar() {
  const { nearest_m: d } = drawRadarView($("radar"), scene);
  const n = scene && scene.targets ? scene.targets.length : 0;
  $("radar-meta").textContent = n ? `${n} ${n === 1 ? "person" : "people"} · ${d.toFixed(2)} m`
    : !radarLive(devices) ? "no radar connected" : scene ? "nobody in view" : "no frame yet";
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
  retry: 0,
  update() {
    const want = app.view === "marvin" && app.sub === "robot" && !document.hidden;
    if (want && !this.es) {
      this.es = new EventSource("/api/robot/stream");
      // a stream refused for good (an error page while the host restarts) is never retried by the browser
      this.es.addEventListener("error", () => {
        if (!this.es || this.es.readyState !== EventSource.CLOSED) return;
        this.es = null;
        clearTimeout(this.retry);
        this.retry = setTimeout(() => this.update(), 3000);
      });
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
    fillOwner(s);
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

const STT_LABELS = { auto: "Auto", parakeet: "Parakeet v3", mlx: "Whisper on the Apple GPU (MLX)", "faster-whisper": "Whisper on the CPU (faster-whisper)" };
const TTS_LABELS = { auto: "Auto (best available)", qwen: "Marvin’s voice (Qwen3-TTS)", say: "macOS say", piper: "Piper", espeak: "espeak-ng" };

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
  const o = voiceOptions || { voices: { piper: [], say: [], qwen: [] }, tts_backends: [] };
  const piperOk = (o.tts_backends.find((b) => b.name === "piper") || {}).available;
  const qwenOk = (o.tts_backends.find((b) => b.name === "qwen") || {}).available;
  // Marvin's own voice reads French and English alike; the other engines have a voice per language
  if (tts === "qwen" || (tts === "auto" && qwenOk)) {
    for (const v of o.voices.qwen || []) opts.push({ value: v.name, label: v.name === "marvin" ? `Marvin${v.installed ? "" : " (downloads once)"}` : v.name });
    fillSelect($("v-voice"), opts, cur || "");
    return;
  }
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
  $("v-vision").value = s.vision_model || "";
  const vh = $("v-vision-hint"), vi = o && o.vision;
  if (!vi || vi.sees == null) vh.textContent = vi && vi.error ? `Cannot tell whether ${vi.model} can see images: ${vi.error}` : "Looks at the images you show Marvin in Talk. Empty: the language model, if it can see images.";
  else if (vi.sees) vh.textContent = `${vi.model} can see images: it looks at the ones you show Marvin in Talk.`;
  else vh.textContent = `${vi.model} cannot see images. Choose a model whose Ollama capabilities include vision.`;
  vh.classList.toggle("bad", !!vi && vi.sees === false);
  fillSelect($("v-stt"), (o ? o.stt_backends : ["auto", "parakeet", "mlx", "faster-whisper"]).map((b) => ({ value: b, label: STT_LABELS[b] || b })), s.stt);
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
  $("v-vocabulary").value = (s.vocabulary || []).join(", ");
  $("v-known").checked = !!s.known_voices_only;
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
    fillWorld(v.settings);
    fillGreetings(v.settings);
    if (v.settings) {
      voiceOptions = await api("/api/voice/options");
      ears.setOptions(voiceOptions);
      fillVoiceForm(app.voiceSettings);
    }
  } catch (e) {
    $("voice-form-note").textContent = `The voice’s settings cannot be read: ${e.message}`;
  }
  renderOllama();
}

// ------------------------------------------------------------------ Ollama and memory's models

function renderOllama() {
  const pill = $("ollama-pill"), line = $("ollama-line"), list = $("ollama-models");
  const o = voiceOptions && voiceOptions.ollama;
  if (!o) {
    pill.textContent = "Unknown";
    pill.className = "pill";
    line.textContent = app.voiceSettings === null ? "Ollama is checked when the voice can run on this host." : "Not checked yet.";
    list.replaceChildren();
    return;
  }
  pill.textContent = o.ok ? "Running" : "Not reachable";
  pill.className = `pill${o.ok ? "" : " attention-pill"}`;
  line.replaceChildren();
  if (o.ok) line.append(`On ${o.host}. Everything the models read stays on that machine.`);
  else { line.append(`${o.error}. `); if (o.fix) line.append(el("code", null, o.fix)); }
  list.replaceChildren(...voiceOptions.llm_models.map((m) => {
    const li = el("li");
    li.append(el("code", null, m));
    const roles = [];
    if (app.voiceSettings && (m === app.voiceSettings.llm_model || m === `${app.voiceSettings.llm_model}:latest`)) roles.push("voice");
    if (memoryModels) {
      if (m === memoryModels.memory_model) roles.push("memory");
      if (m === memoryModels.night_model) roles.push("night");
      if (m === memoryModels.embed_model || m === `${memoryModels.embed_model}:latest`) roles.push("search");
    }
    li.append(el("span", "small muted", roles.join(", ")));
    return li;
  }));
  if (!voiceOptions.llm_models.length && o.ok) list.append(el("li", "small muted", "No model installed yet."));
}

let memoryModels = null;

async function loadMemoryModels() {
  const fields = $("memory-models-fields");
  try {
    const r = await api("/api/memory/settings");
    memoryModels = { memory_model: r.settings.memory_model, night_model: r.settings.night_model, embed_model: r.settings.embed_model };
    $("m-model").value = r.settings.memory_model;
    $("m-night").value = r.settings.night_model;
    setEmbedChoice(r.settings.embed_model);
    fields.disabled = false;
    const w = await api("/api/memory/worker").catch(() => null);
    const e = w && w.embeddings;
    $("m-embed-hint").textContent = !e ? "The embedding model that finds related memories."
      : e.state === "unavailable" ? `Not available: ${e.error || "missing"}. ${e.fix || ""}`
      : `Ready · ${e.dimensions} dimensions · ${e.search}.`;
    renderOllama();
  } catch (e) {
    fields.disabled = true;
    $("memory-models-error").textContent = `Memory’s settings cannot be read: ${e.message}`;
    $("memory-models-error").hidden = false;
  }
}

// the two proposed embedding models, or any other one typed by hand
function setEmbedChoice(model) {
  const choice = $("m-embed-choice");
  const known = [...choice.options].some((o) => o.value && o.value === model);
  choice.value = known ? model : "";
  $("m-embed").value = model;
  $("m-embed").hidden = known;
}

function setupMemoryModels() {
  $("m-embed-choice").addEventListener("change", () => {
    const v = $("m-embed-choice").value;
    $("m-embed").hidden = !!v;
    if (v) $("m-embed").value = v;
    else { $("m-embed").value = ""; $("m-embed").focus(); }
  });
  $("memory-models-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const err = $("memory-models-error");
    err.hidden = true;
    if (!$("m-embed").reportValidity()) return;
    try {
      await post("/api/memory/settings", { memory_model: $("m-model").value.trim(), night_model: $("m-night").value.trim(), embed_model: $("m-embed").value.trim() });
      flash("memory-models-saved", "Saved");
      loadMemoryModels();
    } catch (e) {
      err.textContent = `Not saved: ${e.message}`;
      err.hidden = false;
    }
  });
}

// ------------------------------------------------------------------ you: the owner (an app setting) and the greetings (voice settings)

let ownerChips = null;

function fillOwner(settings) {
  const o = (settings && settings.owner) || { name: "", languages: [] };
  $("o-name").value = o.name || "";
  ownerChips = languageChips("o-lang", o.languages || []);
  $("o-languages").replaceWith(ownerChips);
  ownerChips.id = "o-languages";
}

function fillGreetings(v) {
  const available = !!v;
  for (const id of ["o-greet", "o-welcome"]) $(id).disabled = !available;
  if (!available) { $("o-greet-hint").textContent = "The voice’s settings: available when the voice can run on this host."; return; }
  $("o-greet").checked = v.greet_on_sit !== false;
  $("o-welcome").checked = !!v.welcome_back;
}

function setupOwner() {
  $("owner-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const err = $("owner-error");
    err.hidden = true;
    try {
      const r = await post("/api/settings", { owner: { name: $("o-name").value.trim(), languages: chosenLanguages(ownerChips) } });
      emit("settings", r.settings);
      const v = app.voiceSettings;
      let restarts = false;
      if (v && !$("o-greet").disabled && (v.greet_on_sit !== $("o-greet").checked || !!v.welcome_back !== $("o-welcome").checked)) {
        const rv = await post("/api/voice/settings", { greet_on_sit: $("o-greet").checked, welcome_back: $("o-welcome").checked });
        app.voiceSettings = rv.settings;
        emit("voice", rv.voice);
        restarts = rv.voice.state === "on" || rv.voice.state === "starting";
      }
      flash("owner-saved", restarts ? "Saved. Marvin’s voice restarts." : "Saved");
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
}

// ------------------------------------------------------------------ language and the outside world (voice settings)

function fillWorld(s) {
  const available = !!s;
  $("world-fields").disabled = !available;
  $("world-form").querySelector('button[type="submit"]').disabled = !available;
  if (!available) { $("world-note").textContent = "These are the voice’s settings: available when the voice can run on this host."; return; }
  $("world-form").querySelector(`input[name="w-lang"][value="${s.language || "auto"}"]`).checked = true;
  $("w-tools").checked = s.tools !== false;
  $("w-internet").checked = s.internet !== false;
  $("w-internet").disabled = !$("w-tools").checked;
  $("w-home").value = s.home_place || "";
}

function setupWorld() {
  $("w-tools").addEventListener("change", () => { $("w-internet").disabled = !$("w-tools").checked; });
  $("world-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const err = $("world-error");
    err.hidden = true;
    const lang = ev.target.querySelector('input[name="w-lang"]:checked');
    try {
      const r = await post("/api/voice/settings", {
        language: lang ? lang.value : "auto", tools: $("w-tools").checked, internet: $("w-internet").checked, home_place: $("w-home").value.trim(),
      });
      app.voiceSettings = r.settings;
      emit("voice", r.voice);
      const running = r.voice.state === "on" || r.voice.state === "starting";
      flash("world-saved", running ? "Saved. Marvin’s voice restarts." : "Saved");
      renderOverview();
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
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
      vision_model: $("v-vision").value.trim(),
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
      vocabulary: $("v-vocabulary").value.split(/[,\n]/).map((w) => w.trim()).filter(Boolean),
      known_voices_only: $("v-known").checked,
    };
    try {
      const r = await post("/api/voice/settings", body);
      app.voiceSettings = r.settings;
      emit("voice", r.voice);
      const running = r.voice.state === "on" || r.voice.state === "starting";
      flash("voice-saved", running ? "Saved. Marvin’s voice restarts." : "Saved");
      renderOverview();
      loadVoiceForm();                    // whether the model for images can see them
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
}

// ------------------------------------------------------------------ setup

export function setup() {
  camera.setup();
  ears.setup();
  setupSettings();
  setupVoiceForm();
  setupMemoryModels();
  setupWorld();
  setupOwner();
  on("setup", renderOverview);
  on("state", renderOverview);
  on("state", () => { if (app.view === "marvin" && app.sub === "robot") renderRobotNotice(); });
  on("connection", () => { if (app.view === "marvin" && app.sub === "robot") renderRobotNotice(); });
  on("voice", renderOverview);
  on("settings", renderOverview);
  on("devices", (list) => { if (!robotView.es) renderDevices(list); });
  on("view", ({ view, sub }) => {
    robotView.update();
    if (view !== "marvin") return;
    if (sub === "preferences") { loadSettings(); loadVoiceForm(); }
    if (sub === "voice") { loadVoiceForm(); loadMemoryModels(); }
    if (sub === "robot") renderRobotNotice();
    if (sub === "" || sub === "voice") api("/api/voice").then((v) => { app.voiceSettings = v.settings; renderOverview(); }).catch(() => {});
    if (sub === "robot") requestAnimationFrame(drawScene);
  });
  on("theme", () => { if (app.view === "marvin" && app.sub === "robot") drawScene(); });
  on("resize", () => { if (app.view === "marvin" && app.sub === "robot") drawScene(); });
  document.addEventListener("visibilitychange", () => robotView.update());
  api("/api/robot").then((r) => renderDevices(r.devices)).catch(() => {});
}

// SPDX-License-Identifier: MIT
// Senses: everything Marvin sees, hears and feels, live, for an engineer (docs/ui.md, "Senses"). The lidar with its
// range, the radar, the camera on demand (a live preview only when asked, a few pictures a minute, never kept), what
// the vision model said last; the microphone's level, the voice activity, the end-of-turn decisions, what was heard
// and what became of it; presence and the evidence for it, the room, the vital signs; what Marvin thinks with and how
// long the last answer took; every device and its link. One stream (/api/senses/stream), open only while this screen
// is on screen; nothing is stored.

import { $, app, el, api, on, local, canvas2d, fmtSeconds, fmtTime, timeEl } from "./core.js";
import {
  palette, drawLidar, drawRadar, deviceItem, metric, fmtRate, fmtDistance, radarLive, LIDAR_RANGES,
} from "./sensorviews.js";
import { timingSegments } from "./inspector.js";

const RANGE_KEY = "marvin.senses.range";
const LEVEL_WINDOW_S = 10;
const PREVIEW_EVERY_MS = 2000;
const MAX_UTTERANCES = 20;

const s = {
  scene: null, devices: [], feels: null, hears: null, thinks: null, sees: null,
  utterances: [], lastTurn: null, levels: [], peak: -100, peakAt: 0, task: null, reasoning: null,
};

const visible = () => app.view === "senses" && !document.hidden;

// ------------------------------------------------------------------ sees

function range() {
  const v = local.get(RANGE_KEY, "auto");
  return v === "auto" || LIDAR_RANGES.includes(Number(v)) ? v : "auto";
}

function renderLidar() {
  const lidar = s.scene && s.scene.lidar;
  const lidarDevice = s.devices.some((d) => d.online && d.rates && d.rates.scans > 0);
  const { range: meters, stats } = drawLidar($("s-lidar"), s.scene, { range: range(), radar: radarLive(s.devices) });
  $("s-lidar-empty").hidden = !!lidar || lidarDevice;
  const scans = s.devices.filter((d) => d.online && d.rates && d.rates.scans > 0).reduce((a, d) => a + d.rates.scans, 0);
  $("s-lidar-meta").textContent = lidar ? `${fmtDistance(meters)} view${range() === "auto" ? " (auto)" : ""}` : "no scan yet";
  const m = $("s-lidar-metrics");
  if (!lidar) { m.replaceChildren(); return; }
  m.replaceChildren(
    metric("Nearest", stats && stats.nearest_cm ? String(stats.nearest_cm) : "–", " cm"),
    metric("Bearing", stats && stats.nearest_deg != null ? String(stats.nearest_deg) : "–", "°"),
    metric("Scans", fmtRate(scans), "/s"),
    metric("Points", String(lidar.points), ""),
    metric("Bearings hit", stats ? String(stats.bearings) : "–", "/360"),
    metric("Age", lidar.age_s < 1 ? String(Math.round(lidar.age_s * 1000)) : lidar.age_s.toFixed(1), lidar.age_s < 1 ? " ms" : " s", lidar.age_s > 2),
  );
}

function renderRadar() {
  const live = radarLive(s.devices);
  const targets = (s.scene && s.scene.targets) || [];
  const { nearest_m: near } = drawRadar($("s-radar"), s.scene);
  $("s-radar").hidden = !live && !targets.length;
  $("s-radar-empty").hidden = live || targets.length > 0;
  $("s-radar-meta").textContent = !live && !targets.length ? "" : targets.length
    ? `${targets.length} ${targets.length === 1 ? "person" : "people"} · nearest ${near.toFixed(2)} m` : "nobody in view";
  $("s-targets").replaceChildren(...targets.map(([r, f, v], i) => el("li", null,
    `#${i + 1}  ${(Math.hypot(r, f) / 100).toFixed(2)} m  ${Math.round(Math.atan2(r, f) * 180 / Math.PI)}°  ${v == null ? "" : `${v} cm/s`}`)));
}

let frame = 0;
function drawSees() {
  if (frame) return;
  frame = requestAnimationFrame(() => {
    frame = 0;
    if (!visible()) return;
    renderLidar();
    renderRadar();
  });
}

const SOURCE_NAMES = { computer: "This computer", robot: "The robot" };

function renderCamera() {
  const c = s.sees && s.sees.camera;
  const pill = $("s-cam-pill");
  if (!c) { pill.textContent = "–"; $("s-cam-line").textContent = "–"; return; }
  pill.textContent = c.source ? `${SOURCE_NAMES[c.source] || c.source}` : c.setting === "off" ? "Off" : "No camera";
  pill.className = `pill${c.source ? "" : " attention-pill"}`;
  const last = c.last;
  $("s-cam-line").textContent = c.source
    ? `${c.detail}. It looks only when asked${last ? `; last picture ${fmtTime(last.t)} (${last.purpose === "look" ? "Marvin looked" : "a test"})` : ""}.`
    : c.setting === "off" ? "The camera is off in Marvin > Voice & models: Marvin cannot look." : `No camera: ${c.detail}.${c.fix ? ` ${c.fix}` : ""}`;
  $("s-cam-take").disabled = !c.source || preview.busy;
  $("s-cam-preview").disabled = !c.source;
  const look = s.sees.look;
  const lk = $("s-look");
  lk.replaceChildren();
  if (!look) lk.textContent = "Nothing yet: ask Marvin “what do you see?” with a camera connected.";
  else {
    lk.append(timeEl(look.t), ` · ${look.ok ? "the vision model said" : "the look failed"}: `, el("q", null, look.text || "–"));
  }
}

const preview = {
  timer: 0,
  busy: false,
  on: false,
  async shoot() {
    if (this.busy) return;
    this.busy = true;
    $("s-cam-take").disabled = true;
    const err = $("s-cam-error");
    try {
      const rs = await fetch("/api/camera/snapshot", {
        method: "POST", credentials: "same-origin", cache: "no-store", headers: { "Content-Type": "application/json" }, body: "{}",
      });
      const r = await rs.json().catch(() => ({}));
      if (!rs.ok) throw new Error(`${r.error || `HTTP ${rs.status}`}${r.fix ? `. ${r.fix}` : ""}`);
      err.hidden = true;
      $("s-cam-img").src = r.image;           // a data: URL, in this page only
      const p = r.picture;
      $("s-cam-meta").textContent = `${p.width} × ${p.height} · ${fmtSeconds(p.seconds)} · ${clock(Date.now() / 1000)} · not kept`;
      $("s-cam-frame").hidden = false;
      if (s.sees) s.sees.camera = r.camera;
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
      this.stop();
    } finally {
      this.busy = false;
      renderCamera();
    }
  },
  start() {
    this.on = true;
    $("s-cam-preview").checked = true;
    $("s-cam-live").hidden = false;
    const loop = async () => {
      if (!this.on || !visible()) { this.stop(); return; }
      await this.shoot();
      if (this.on) this.timer = setTimeout(loop, PREVIEW_EVERY_MS);
    };
    loop();
  },
  stop() {
    this.on = false;
    clearTimeout(this.timer);
    $("s-cam-preview").checked = false;
    $("s-cam-live").hidden = true;
  },
};

// ------------------------------------------------------------------ hears

const STATUS_WORDS = { idle: "Idle", listening: "Listening", thinking: "Thinking", speaking: "Speaking", off: "Off" };

function vadState() {
  const h = s.hears;
  if (!h || h.status === "off") return ["off", h && h.state === "starting" ? "starting" : "voice off"];
  if (h.muted) return ["muted", "muted"];
  const l = s.levels.length ? s.levels[s.levels.length - 1] : null;
  if (l && l.gated) return ["deaf", "deaf: Marvin speaks"];
  if (l && l.speech) return ["speech", "speech"];
  return ["silence", "silence"];
}

function renderVad() {
  const [state, word] = vadState();
  const chip = $("s-vad");
  chip.dataset.state = state;
  chip.textContent = `VAD · ${word}`;
}

function onLevel(l) {
  const t = performance.now() / 1000;
  const db = l.dbfs != null ? l.dbfs : l.mic > 0 ? -60 + l.mic * 50 : -100;
  s.levels.push({ t, db, speech: !!l.speech, gated: !!l.gated });
  while (s.levels.length && t - s.levels[0].t > LEVEL_WINDOW_S) s.levels.shift();
  if (db >= s.peak || t - s.peakAt > 1.5) { s.peak = db; s.peakAt = t; }
  if (!visible()) return;
  const pct = (x) => `${Math.max(0, Math.min(100, ((x + 60) / 60) * 100))}%`;
  const meter = $("s-meter");
  $("s-meter-fill").style.width = pct(db);
  $("s-meter-peak").style.left = pct(s.peak);
  meter.dataset.speech = String(!!l.speech);
  meter.setAttribute("aria-valuenow", String(Math.round(Math.max(-60, db))));
  $("s-dbfs").textContent = db <= -99 ? "muted" : `${db.toFixed(1)} dBFS`;
  renderVad();
  drawLevels();
}

function drawLevels() {
  const g = canvas2d($("s-level"));
  if (!g) return;
  const { ctx, w, h } = g, col = palette();
  const now = performance.now() / 1000;
  const x = (t) => w - ((now - t) / LEVEL_WINDOW_S) * w;
  const y = (db) => h - 2 - (Math.max(0, Math.min(60, db + 60)) / 60) * (h - 4);
  ctx.fillStyle = col.fov;
  for (const p of s.levels) if (p.speech) ctx.fillRect(x(p.t) - 3, 0, 6, h);       // speech, shaded
  ctx.strokeStyle = col.line;
  ctx.lineWidth = 1;
  for (const db of [-45, -30, -15]) { ctx.beginPath(); ctx.moveTo(0, y(db) + 0.5); ctx.lineTo(w, y(db) + 0.5); ctx.stroke(); }
  ctx.strokeStyle = col.point;
  ctx.lineWidth = 1.6;
  ctx.beginPath();
  s.levels.forEach((p, i) => (i ? ctx.lineTo(x(p.t), y(p.db)) : ctx.moveTo(x(p.t), y(p.db))));
  ctx.stroke();
}

const pctText = (p) => (p == null ? "–" : `${Math.round(p * 100)}%`);

function renderEars() {
  const h = s.hears;
  const off = $("s-voice-off");
  if (!h) return;
  off.hidden = !h.why;
  if (h.why) {
    $("s-voice-off-title").textContent = h.status === "off" ? "Marvin hears nothing now." : "The microphone is closed.";
    $("s-voice-off-text").textContent = h.why;
  }
  const lastUtt = s.utterances[0];
  const wake = s.utterances.find((u) => u.wake);
  const t = s.lastTurn;
  const turnText = t ? `${t.complete ? "complete" : "not finished"} · p ${t.probability.toFixed(2)} · ${Math.round(t.turn_s * 1000)} ms`
    : lastUtt && !lastUtt.turn ? "silence timers (no Smart Turn)" : "no decision yet";
  const ears = $("s-ears");
  ears.replaceChildren(
    metric("Speech recognition", h.stt || (h.status === "off" ? "–" : h.stt_setting), ""),
    metric("Last recognition", lastUtt && lastUtt.stt_s != null ? fmtSeconds(lastUtt.stt_s) : "–", lastUtt && lastUtt.speculative ? " (began in the pause)" : ""),
    metric("Voice activity", lastUtt ? lastUtt.vad : "–", ""),
    metric("End of turn", t ? `Smart Turn: ${turnText}` : turnText, "", t && !t.complete),
    metric("Wake word", wake ? `“Marvin” at ${fmtTime(wake.t)}` : h.wake ? "waiting for “Marvin”" : "off: no name needed", ""),
    metric("Language", lastUtt && lastUtt.language ? `${lastUtt.language} ${lastUtt.language_prob != null ? pctText(lastUtt.language_prob) : ""}` : h.language, h.language === "auto" ? " · auto" : ` · forced ${h.language}`),
    metric("Speaker", lastUtt && lastUtt.speaker ? `${lastUtt.speaker}${lastUtt.speaker_score != null ? ` ${lastUtt.speaker_score.toFixed(2)}` : ""}` : "not judged", h.known_voices_only ? " · known voices only" : ""),
    metric("Device", h.device ? (h.device === "computer" ? "this computer" : h.device) : "–", ""),
  );
  renderVad();
}

/** The time to the second, in the app's clock (12 or 24 hours). */
function clock(t) {
  const d = new Date(t * 1000);
  const sec = String(d.getSeconds()).padStart(2, "0");
  const hm = fmtTime(t);
  return app.settings.clock === "12h" ? hm.replace(/ (am|pm)$/, `:${sec} $1`) : `${hm}:${sec}`;
}

const OUTCOMES = { heard: "Answered", listening: "Name only", ignored: "Ignored", dropped: "Not judged" };

function utteranceItem(u) {
  const li = el("li", "utterance");
  const head = el("div", "utterance-head");
  head.append(timeEl(u.t, clock(u.t)),
    el("span", `outcome ${u.outcome}`, OUTCOMES[u.outcome] || u.outcome));
  if (u.wake) head.append(el("span", "outcome", "“Marvin”"));
  li.append(head);
  if (u.text) li.append(el("q", null, u.text));
  if (u.reason && u.outcome !== "heard") li.append(el("p", "why", u.reason));
  const facts = [
    `${u.duration_s.toFixed(2)} s`, u.dbfs != null ? `${u.dbfs.toFixed(1)} dBFS` : null,
    u.stt ? `${u.stt} ${u.stt_s != null ? fmtSeconds(u.stt_s) : ""}${u.speculative ? " (pause)" : ""}` : null,
    u.language ? `${u.language}${u.language_prob != null ? ` ${pctText(u.language_prob)}` : ""}` : null,
    u.speaker ? `voice: ${u.speaker}${u.speaker_score != null ? ` ${u.speaker_score.toFixed(2)}` : ""}` : null,
    u.turn ? `turn: ${u.turn}` : "turn: silence", `vad: ${u.vad}`,
  ].filter(Boolean);
  li.append(el("p", "facts", facts.join(" · ")));
  return li;
}

function renderUtterances() {
  $("s-utterances").replaceChildren(...s.utterances.map(utteranceItem));
  $("s-utt-empty").hidden = s.utterances.length > 0;
  $("s-utt-empty").textContent = s.hears && s.hears.status === "off" ? "The voice is off: nothing is heard." : "Nothing heard since the voice started.";
  $("s-utt-meta").textContent = s.utterances.length ? `last ${s.utterances.length}` : "";
}

function onHearing(h) {
  if (h.kind === "turn") {
    s.lastTurn = h;
  } else {
    s.utterances.unshift(h);
    s.utterances.length = Math.min(s.utterances.length, MAX_UTTERANCES);
    if (visible()) renderUtterances();
  }
  if (visible()) renderEars();
}

// ------------------------------------------------------------------ feels

const SOURCES = { radar: "Radar", pir: "Motion (PIR)", none: "No presence sensor" };

function renderFeels() {
  const f = s.feels;
  if (!f) return;
  const p = f.presence;
  $("s-presence-source").textContent = SOURCES[p.source] || p.source;
  $("s-presence-word").textContent = p.source === "none" ? "Unknown" : !p.present ? "Nobody here"
    : p.seated ? `Here, seated${p.distance_m != null ? ` · ${p.distance_m.toFixed(2)} m` : ""}` : `Here${p.distance_m != null ? ` · ${p.distance_m.toFixed(2)} m` : ""}`;
  $("s-reasons").replaceChildren(...p.reasoning.map((r) => el("li", null, r)));
  const k = f.sensors;
  const chips = [["Lidar", k.lidar], ["Radar", k.radar], ["Vital signs", k.vitals], ["PIR", k.pir], ["Climate", k.climate], ["Light", k.light], ["Camera: not for presence", false]];
  $("s-sensor-chips").replaceChildren(...chips.map(([n, on]) => {
    const li = el("li", on ? "on" : "", n);
    li.setAttribute("aria-label", `${n}: ${on ? "live" : "not connected"}`);
    return li;
  }));
  const r = f.room;
  const room = $("s-room");
  const any = r && (r.motion_sensor || r.climate_sensor || r.light_sensor);
  $("s-room-empty").hidden = !!any;
  room.hidden = !any;
  $("s-room-meta").textContent = !any ? "" : `${r.simulated ? "simulated · " : ""}${r.fresh ? `${Math.round(r.age_s)} s ago` : "no longer live"}`;
  if (any) {
    const items = [];
    if (r.climate_sensor) {
      items.push(metric("Temperature", r.temperature_c == null ? "–" : r.temperature_c.toFixed(1), " °C"));
      items.push(metric("Humidity", r.humidity == null ? "–" : String(Math.round(r.humidity)), " %"));
    }
    if (r.light_sensor) items.push(metric("Light", r.light_word || "–", r.light == null ? "" : ` ${Math.round(r.light * 100)}%`));
    if (r.motion_sensor) items.push(metric("Motion", r.warming_up ? "warming up" : r.motion ? "now" : r.motion_age_s == null ? "none yet" : `${Math.round(r.motion_age_s)} s ago`, ""));
    room.replaceChildren(...items);
  }
  const v = f.vitals;
  $("s-vitals-empty").hidden = v.sensor;
  $("s-vitals").hidden = !v.sensor;
  const sv = s.scene && s.scene.vitals;
  $("s-vitals-meta").textContent = !v.sensor ? "" : sv && sv.valid ? (sv.distance_cm ? `at ${(sv.distance_cm / 100).toFixed(2)} m` : "measured") : "not readable now";
  if (v.sensor) {
    $("s-vitals").replaceChildren(
      metric("Breathing", v.breath_rate == null ? "–" : String(Math.round(v.breath_rate)), " /min"),
      metric("Heart", v.heart_rate == null ? "–" : String(Math.round(v.heart_rate)), " /min"),
      metric("Waves", sv && sv.waves ? String(sv.waves.length) : "0", " samples"),
    );
  }
}

// ------------------------------------------------------------------ thinks

const LEVELS = { off: "Off", low: "Low", medium: "Medium", high: "High" };

function renderThinks() {
  const t = s.thinks, h = s.hears;
  if (!t || !h) return;
  const kv = $("thinks-kv");
  const item = (label, value, note) => {
    const d = el("div");
    const dd = el("dd", null, value);
    if (note) dd.append(el("small", null, note));
    d.append(el("dt", null, label), dd);
    return d;
  };
  const a = t.last_answer;
  const loaded = !a ? "no answer yet" : a.load_s == null ? "" : a.load_s > 0.5 ? `loaded for the last answer (${fmtSeconds(a.load_s)})` : "already loaded at the last answer";
  const rs = s.reasoning && s.reasoning.settings;
  const task = s.task;
  kv.replaceChildren(
    item("Voice", STATUS_WORDS[t.status] || t.status, h.why || (h.muted ? "muted" : "")),
    item("Model", t.model, loaded),
    item("Reasoning", rs ? `talk ${LEVELS[rs.reasoning] || rs.reasoning} · tasks ${LEVELS[rs.task_reasoning] || rs.task_reasoning}` : "–",
      s.reasoning && s.reasoning.model && s.reasoning.model.thinking && s.reasoning.model.thinking.thinks === false ? "this model does not think" : ""),
    item("Background task", task ? task.title : "none", task ? task.activity || task.status : ""),
    item("Speech recognition", h.stt || h.stt_setting || "–", h.stt ? "" : "the setting; the engine shows once the voice runs"),
    item("Speech", h.tts || h.tts_setting || "–", h.tts_voice ? `voice ${h.tts_voice}` : ""),
  );
  const stages = $("thinks-stages");
  stages.replaceChildren();
  if (!a) {
    stages.append(el("p", "small muted", "No answer yet since the host started."));
    $("thinks-meta").textContent = "";
    return;
  }
  $("thinks-meta").textContent = `${fmtTime(a.t)}${a.first_word_s != null ? ` · first word after ${fmtSeconds(a.first_word_s)}` : ""}${a.interrupted ? " · interrupted" : ""}`;
  const segs = timingSegments(a);
  if (!segs.length) { stages.append(el("p", "small muted", "Not measured (a typed question, or nothing was said).")); return; }
  const sum = Math.max(a.first_word_s || 0, segs.reduce((x, g) => x + g.v, 0));
  const bar = el("div", "tbar");
  bar.setAttribute("role", "img");
  bar.setAttribute("aria-label", segs.map((g) => `${g.label} ${fmtSeconds(g.v)}`).join(", "));
  let shade = 0;
  const cls = segs.map((g) => (g.tool ? "tool" : `t${Math.min(5, shade++)}`));
  segs.forEach((g, i) => {
    const seg = el("span", `tseg ${cls[i]}`);
    seg.style.flexGrow = String(Math.max(0.001, g.v / sum));
    seg.title = `${g.label}: ${fmtSeconds(g.v)}`;
    bar.append(seg);
  });
  const legend = el("ul", "tlegend");
  segs.forEach((g, i) => {
    const li = el("li");
    li.append(el("i", `sw ${cls[i]}`), el("span", null, g.label), el("b", null, fmtSeconds(g.v)));
    legend.append(li);
  });
  stages.append(bar, legend);
}

// ------------------------------------------------------------------ devices

function renderDevices() {
  const list = s.devices;
  $("s-devices").replaceChildren(...list.map(deviceItem));
  $("s-devices-empty").hidden = list.length > 0;
  const online = list.filter((d) => d.online).length;
  $("s-devices-summary").textContent = list.length ? `${online} of ${list.length} connected` : "";
  const h = s.hears;
  $("s-audio-route").textContent = !h ? "" : h.status === "off" ? "Audio: the voice is off, no microphone or speaker is open."
    : `Audio: Marvin hears and speaks through ${h.device === "computer" ? "this computer’s microphone and speaker" : `the robot ${h.device}`}; earcons ${h.earcons === "device" ? "on that device" : h.earcons === "buzzer" ? "on a bench board’s buzzer" : "in this app only"}.`;
}

// ------------------------------------------------------------------ the stream

function setLive(state, text) {
  const p = $("senses-live");
  p.dataset.state = state;
  $("senses-live-text").textContent = text;
}

function applySummary(m) {
  s.feels = m.feels;
  s.hears = { ...(s.hears || {}), ...m.hears };
  s.thinks = m.thinks;
  if (m.thinks && m.thinks.reasoning) s.reasoning = m.thinks.reasoning;
  if (m.thinks && "task" in m.thinks) s.task = m.thinks.task;
  if ("look" in m && s.sees) {
    const changed = JSON.stringify(s.sees.look) !== JSON.stringify(m.look);
    s.sees.look = m.look;
    if (changed && visible()) renderCamera();
  }
}

function renderAll() {
  renderLidar();
  renderRadar();
  renderCamera();
  renderEars();
  renderUtterances();
  renderFeels();
  renderThinks();
  renderDevices();
}

const stream = {
  es: null,
  retry: 0,
  async load() {
    try {
      const r = await api("/api/senses");
      s.devices = r.devices;
      s.scene = r.scene;
      s.sees = r.sees;
      applySummary(r);
      s.utterances = (r.hears.recent || []).filter((h) => h.kind === "utterance").reverse().slice(0, MAX_UTTERANCES);
      s.lastTurn = (r.hears.recent || []).filter((h) => h.kind === "turn").pop() || null;
      if (r.hears.level) onLevel(r.hears.level);
      if (visible()) renderAll();
    } catch (e) {
      setLive("lost", "Cannot read the senses");
    }
  },
  update() {
    const want = visible();
    if (want && !this.es) {
      setLive("connecting", "Connecting");
      const es = this.es = new EventSource("/api/senses/stream");
      const json = (m) => JSON.parse(m.data);
      es.addEventListener("open", () => setLive("live", "Live"));
      es.addEventListener("error", () => {
        setLive("lost", "Reconnecting");
        if (this.es !== es || es.readyState !== EventSource.CLOSED) return;
        this.es = null;                         // refused for good: the browser does not retry, this does
        clearTimeout(this.retry);
        this.retry = setTimeout(() => this.update(), 3000);
      });
      es.addEventListener("scene", (m) => {
        s.scene = json(m);
        drawSees();
        const scans = s.devices.filter((d) => d.online && d.rates && d.rates.scans > 0).reduce((a, d) => a + d.rates.scans, 0);
        setLive("live", scans ? `Live · ${fmtRate(scans)} scans/s` : "Live");
      });
      es.addEventListener("devices", (m) => { s.devices = json(m); renderDevices(); });
      es.addEventListener("senses", (m) => { applySummary(json(m)); renderFeels(); renderThinks(); renderEars(); });
      es.addEventListener("level", (m) => onLevel(json(m)));
      es.addEventListener("hearing", (m) => onHearing(json(m)));
      es.addEventListener("voice", (m) => {
        const v = json(m);
        if (s.hears) { s.hears.status = v.state === "on" ? v.status : "off"; s.hears.muted = v.muted; }
        renderVad();
      });
      es.addEventListener("task", (m) => {
        const t = json(m);
        s.task = ["queued", "running"].includes(t.status) ? t : s.task && s.task.id === t.id ? null : s.task;
        renderThinks();
      });
      es.addEventListener("camera", (m) => {
        if (s.sees && s.sees.camera) { s.sees.camera.last = json(m); renderCamera(); }
      });
      this.load();
    } else if (!want && this.es) {
      this.es.close();
      this.es = null;
      clearTimeout(this.retry);
      preview.stop();
      setLive("connecting", "Paused");
    }
  },
};

// ------------------------------------------------------------------ setup

export function setup() {
  const r = range();
  for (const input of document.querySelectorAll('input[name="s-range"]')) {
    input.checked = input.value === String(r);
    input.addEventListener("change", () => { if (input.checked) { local.set(RANGE_KEY, input.value); renderLidar(); } });
  }
  $("s-cam-take").addEventListener("click", () => preview.shoot());
  $("s-cam-preview").addEventListener("change", (ev) => { if (ev.target.checked) preview.start(); else preview.stop(); });
  on("view", () => stream.update());
  on("theme", () => { if (visible()) renderAll(); });
  on("resize", () => { if (visible()) { renderLidar(); renderRadar(); drawLevels(); } });
  document.addEventListener("visibilitychange", () => stream.update());
}

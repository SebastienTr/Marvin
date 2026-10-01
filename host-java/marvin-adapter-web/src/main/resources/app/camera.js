// SPDX-License-Identifier: MIT
// Marvin's camera (docs/vision.md): which camera the look tool uses (the voice's "camera" setting), what each camera
// can do now, and the camera test, which shows the one picture taken in this page only. Each picture Marvin takes is
// said in a toast, so a picture is never taken unseen.

import { $, app, el, api, post, on, emit, toast, fmtSeconds } from "./core.js";

const SOURCE_NAMES = { computer: "This computer", robot: "The robot" };
let camera = null;

function cameraLabel(c) {
  if (!c) return "–";
  if (c.source) return `${SOURCE_NAMES[c.source] || c.source}${c.setting === "auto" ? " (auto)" : ""}`;
  return c.setting === "off" ? "Off" : "Not available";
}

/** One line for the Overview: which camera, or why none. */
export function cameraLine() {
  const c = camera;
  if (!c) return "–";
  if (c.source) return `${c.detail}. Looks only when you ask.`;
  return c.setting === "off" ? "Off: Marvin cannot look" : `Not available: ${c.detail}`;
}

function render() {
  const c = camera;
  const pill = $("camera-pill");
  pill.textContent = cameraLabel(c);
  pill.className = `pill${c && !c.source && c.setting !== "off" ? " attention-pill" : ""}`;
  if ($("camera-line")) $("camera-line").textContent = cameraLine();
  if (!c) return;
  const choice = document.querySelector(`input[name="cam"][value="${c.setting}"]`);
  if (choice && !$("camera-form").dataset.dirty) choice.checked = true;
  const hint = $("camera-hint");
  hint.textContent = c.source ? `Now: ${c.detail}.` : `${c.detail}.${c.fix ? ` ${c.fix}` : ""}`;
  hint.classList.toggle("bad", !c.source && c.setting !== "off");
  $("camera-sources").replaceChildren(...c.sources.map((s) => {
    const li = el("li");
    const text = el("span");
    text.append(el("b", null, SOURCE_NAMES[s.name] || s.name), el("span", "small muted", s.available ? s.detail : `${s.detail}${s.fix ? `. ${s.fix}` : ""}`));
    li.append(text, el("span", `state-pill ${s.available ? "up" : "unavailable"}`, s.available ? "Ready" : "Not available"));
    return li;
  }));
  $("camera-test").disabled = !c.source;
}

async function load() {
  try {
    camera = await api("/api/camera");
  } catch (e) {
    camera = null;
    $("camera-hint").textContent = `The camera cannot be read: ${e.message}`;
  }
  render();
}

function when(t) {
  return new Date(t * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

async function test() {
  const err = $("camera-error"), btn = $("camera-test");
  err.hidden = true;
  btn.disabled = true;
  const label = btn.textContent;
  btn.textContent = "Taking a picture…";
  try {
    const rs = await fetch("/api/camera/snapshot", {
      method: "POST", credentials: "same-origin", cache: "no-store", headers: { "Content-Type": "application/json" }, body: "{}",
    });
    const r = await rs.json().catch(() => ({}));
    if (!rs.ok) throw new Error(`${r.error || `HTTP ${rs.status}`}${r.fix ? `. ${r.fix}` : ""}`);
    camera = r.camera;
    const p = r.picture;
    $("camera-img").src = r.image;          // a data: URL, shown in this page only, gone with it
    $("camera-meta").textContent = `${p.detail} · ${p.width} × ${p.height} · ${fmtSeconds(p.seconds)} · not kept`;
    $("camera-shot").hidden = false;
  } catch (e) {
    err.textContent = e.message;
    err.hidden = false;
    load();
  } finally {
    btn.textContent = label;
    render();
  }
}

export function setup() {
  $("camera-form").addEventListener("change", () => { $("camera-form").dataset.dirty = "1"; });
  $("camera-test").addEventListener("click", test);
  $("camera-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const err = $("camera-error");
    err.hidden = true;
    const choice = ev.target.querySelector('input[name="cam"]:checked');
    try {
      const r = await post("/api/voice/settings", { camera: choice ? choice.value : "auto" });
      app.voiceSettings = r.settings;
      emit("voice", r.voice);
      delete $("camera-form").dataset.dirty;
      const running = r.voice.state === "on" || r.voice.state === "starting";
      $("camera-saved").textContent = running ? "Saved. Marvin’s voice restarts." : "Saved";
      setTimeout(() => { $("camera-saved").textContent = ""; }, 3000);
      load();
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  });
  // a picture taken: by Marvin (the look tool) or by a camera test, maybe in another tab
  on("camera", (p) => {
    if (camera) camera.last = p;
    if (p.purpose === "look") toast(`Marvin took a picture with ${p.source === "robot" ? "the robot’s" : "this computer’s"} camera at ${when(p.t)}.`);
  });
  on("view", ({ view, sub }) => {
    if (view === "marvin" && (sub === "voice" || sub === "")) load();
  });
  on("devices", () => { if (app.view === "marvin" && (app.sub === "voice" || app.sub === "")) load(); });
}

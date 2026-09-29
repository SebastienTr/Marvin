// SPDX-License-Identifier: MIT
// Showing Marvin an image (docs/voice.md "Showing Marvin an image"). The owner picks a photo (the image button, or on a
// phone the camera or the photo library), drops one on the conversation, or pastes one into the composer. This page
// makes it small first (longest side 1280 px, JPEG at 0.85: redrawing it also leaves out where and when it was taken)
// and sends it to the host, which keeps it in memory for the next question, typed or spoken. The host says whether it
// is waiting (the voice's `image`); once a question took it, the preview goes and the question's bubble shows it.
// The thumbnails stay in this browser tab only (sessionStorage): the host never keeps the image.

import { $, app, el, on, emit, phone, fmtBytes } from "./core.js";

export const MAX_SIDE = 1280;
export const QUALITY = 0.85;
const THUMB_SIDE = 240;
const THUMBS_KEY = "marvin.thumbs";
const THUMBS_KEPT = 16;

// ------------------------------------------------------------------ thumbnails, by the SHA-256 the host reports

const thumbs = new Map(readThumbs());

function readThumbs() {
  try { return JSON.parse(window.sessionStorage.getItem(THUMBS_KEY) || "[]"); } catch (e) { return []; }
}

export function rememberThumb(sha, url) {
  if (!sha || !url) return;
  thumbs.delete(sha);
  thumbs.set(sha, url);
  while (thumbs.size > THUMBS_KEPT) thumbs.delete(thumbs.keys().next().value);
  try { window.sessionStorage.setItem(THUMBS_KEY, JSON.stringify([...thumbs])); } catch (e) { /* not kept: fine */ }
}

/** The thumbnail of an image shown in this tab, or null. */
export function thumbFor(image) {
  return image && image.sha256 ? thumbs.get(image.sha256) || null : null;
}

/** "1280 × 960 · 212 KB" */
export function imageSummary(image) {
  if (!image) return "";
  const parts = [];
  if (image.width && image.height) parts.push(`${image.width} × ${image.height}`);
  if (image.bytes) parts.push(fmtBytes(image.bytes));
  return parts.join(" · ");
}

/** The image of a bubble: its thumbnail when this tab has it, else a quiet placeholder with its size. */
export function imageFigure(image, label = "The image you showed Marvin") {
  const fig = el("span", "msg-image");
  const url = thumbFor(image);
  if (url) {
    const img = el("img");
    img.src = url;
    img.alt = label;
    img.decoding = "async";
    fig.append(img);
  } else {
    fig.classList.add("placeholder");
    fig.insertAdjacentHTML("beforeend", '<svg class="icon" aria-hidden="true"><use href="#i-image"/></svg>');
    fig.append(el("span", null, `Image${image && image.width ? ` · ${image.width} × ${image.height}` : ""}`));
  }
  return fig;
}

// ------------------------------------------------------------------ making it small

function decode(file) {
  if (window.createImageBitmap) {
    return createImageBitmap(file, { imageOrientation: "from-image" }).catch(() => decodeWithImg(file));
  }
  return decodeWithImg(file);
}

function decodeWithImg(file) {
  // a data: URL, as the page's content policy allows images from data: but not blob:
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onerror = () => reject(new Error("This image cannot be read."));
    r.onload = () => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error("This image format cannot be read here: try a JPEG or a PNG."));
      img.src = r.result;
    };
    r.readAsDataURL(file);
  });
}

function draw(src, w, h) {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  const g = c.getContext("2d");
  g.fillStyle = "#ffffff";              // a transparent PNG on white, not black
  g.fillRect(0, 0, w, h);
  g.imageSmoothingQuality = "high";
  g.drawImage(src, 0, 0, w, h);
  return c;
}

/** The image the host gets: at most MAX_SIDE a side, JPEG; and a small thumbnail for the bubble. */
export async function prepare(file) {
  if (file.type && !file.type.startsWith("image/")) throw new Error("That is not an image.");
  const src = await decode(file);
  const w0 = src.width, h0 = src.height;
  if (!w0 || !h0) throw new Error("This image is empty.");
  const scale = Math.min(1, MAX_SIDE / Math.max(w0, h0));
  const w = Math.max(1, Math.round(w0 * scale)), h = Math.max(1, Math.round(h0 * scale));
  const canvas = draw(src, w, h);
  const blob = await new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("This image cannot be prepared."))), "image/jpeg", QUALITY));
  const t = Math.min(1, THUMB_SIDE / Math.max(w, h));
  const thumb = draw(canvas, Math.max(1, Math.round(w * t)), Math.max(1, Math.round(h * t))).toDataURL("image/jpeg", 0.75);
  if (src.close) src.close();
  return { blob, width: w, height: h, thumb, original: { width: w0, height: h0, bytes: file.size } };
}

// ------------------------------------------------------------------ the preview above the composer

const local = { state: "none", thumb: null, sha: null, token: 0 };

function voiceOn() {
  return !!app.voice && app.voice.state === "on";
}

function setThumb(url) {
  const box = $("attach-thumb");
  box.replaceChildren();
  if (url) {
    const img = el("img");
    img.src = url;
    img.alt = "";
    box.append(img);
  } else {
    box.insertAdjacentHTML("beforeend", '<svg class="icon" aria-hidden="true"><use href="#i-image"/></svg>');
  }
}

function render() {
  const box = $("attach");
  const waiting = app.voice && app.voice.image;
  box.hidden = local.state === "none";
  box.dataset.state = local.state;
  $("attach-remove").disabled = local.state === "sending";
  const input = $("ask-input");
  if (local.state === "preparing") {
    $("attach-note").textContent = "Preparing the image…";
    $("attach-meta").textContent = "";
  } else if (local.state === "sending") {
    $("attach-note").textContent = "Sending the image…";
  } else if (local.state === "ready" && waiting) {
    $("attach-note").textContent = "Goes with your next question, typed or spoken.";
    $("attach-meta").textContent = [imageSummary(waiting), waiting.model ? `seen by ${waiting.model}` : ""].filter(Boolean).join(" · ");
  }
  if (voiceOn()) input.placeholder = local.state === "ready" ? "Ask about the image…" : "Write to Marvin…";
  $("attach-button").disabled = !voiceOn() || local.state === "preparing" || local.state === "sending";
}

function showError(message, refused) {
  const p = $("attach-error");
  p.replaceChildren(el("span", null, message));
  if (refused) {
    const a = el("a", "quiet-link", "Open Marvin > Voice");
    a.href = "#marvin/voice";
    p.append(" ", a);
  }
  p.hidden = false;
}

function clearError() {
  $("attach-error").hidden = true;
}

function clear() {
  local.state = "none";
  local.thumb = null;
  local.sha = null;
  local.token++;
  render();
}

let inFlight = Promise.resolve();

/** Resolves once no image is being prepared or sent (a typed question waits for its image). */
export function settled() {
  return inFlight;
}

/** Prepares `file` and hands it to the host for the next question. */
export function attach(file) {
  inFlight = attachNow(file);
  return inFlight;
}

async function attachNow(file) {
  if (!file) return;
  clearError();
  closeMenu();
  if (!voiceOn()) { showError("Turn the voice on to show Marvin an image."); return; }
  const token = ++local.token;
  local.state = "preparing";
  setThumb(null);
  render();
  try {
    const p = await prepare(file);
    if (token !== local.token) return;
    local.thumb = p.thumb;
    setThumb(p.thumb);
    local.state = "sending";
    $("attach-meta").textContent = `${p.width} × ${p.height} · ${fmtBytes(p.blob.size)}`;
    render();
    const r = await fetch("/api/voice/image", {
      method: "POST", credentials: "same-origin", cache: "no-store", headers: { "Content-Type": "image/jpeg" }, body: p.blob,
    });
    const body = await r.json().catch(() => ({}));
    if (token !== local.token) return;
    if (!r.ok) {
      clear();
      showError(body.error || `The image was not sent (HTTP ${r.status}).`, r.status === 422);
      return;
    }
    local.sha = body.image && body.image.sha256;
    rememberThumb(local.sha, p.thumb);
    local.state = "ready";
    if (body.voice) emit("voice", body.voice);
    render();
  } catch (e) {
    if (token !== local.token) return;
    clear();
    showError(e.message || "The image could not be prepared.");
  }
}

async function remove() {
  const had = local.state === "ready";
  clear();
  clearError();
  if (!had) return;
  try {
    const r = await fetch("/api/voice/image/remove", {
      method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: "{}",
    });
    const body = await r.json().catch(() => ({}));
    if (body.voice) emit("voice", body.voice);
  } catch (e) { /* the host drops it with the next question or after ten minutes anyway */ }
  $("ask-input").focus();
}

/** The host's word on the waiting image: taken by a question, dropped, or attached elsewhere. */
function onVoice(v) {
  const waiting = v && v.state === "on" ? v.image : null;
  if (local.state === "ready" && (!waiting || waiting.sha256 !== local.sha)) clear();
  if (waiting && local.state === "none") {
    // attached before this page was opened, or from another device
    local.state = "ready";
    local.sha = waiting.sha256;
    setThumb(thumbFor(waiting));
  }
  if (!v || v.state !== "on") clearError();
  render();
}

// ------------------------------------------------------------------ picking, dropping, pasting

function closeMenu() {
  const m = $("attach-menu");
  if (m.hidden) return;
  m.hidden = true;
  $("attach-button").setAttribute("aria-expanded", "false");
}

function openMenu() {
  const m = $("attach-menu");
  m.hidden = false;
  $("attach-button").setAttribute("aria-expanded", "true");
  $("attach-camera").focus();
}

const touch = window.matchMedia("(pointer: coarse)");

function pick(input) {
  closeMenu();
  input.value = "";
  input.click();
}

function imageFile(list) {
  return [...(list || [])].find((f) => f && (!f.type || f.type.startsWith("image/"))) || null;
}

function setupDrop() {
  const panel = $("transcript").closest(".conversation");
  let depth = 0;
  const hasFiles = (ev) => ev.dataTransfer && [...ev.dataTransfer.types].includes("Files");
  panel.addEventListener("dragenter", (ev) => {
    if (!hasFiles(ev) || !voiceOn()) return;
    ev.preventDefault();
    depth++;
    panel.classList.add("dropping");
  });
  panel.addEventListener("dragover", (ev) => {
    if (!hasFiles(ev) || !voiceOn()) return;
    ev.preventDefault();
    ev.dataTransfer.dropEffect = "copy";
  });
  panel.addEventListener("dragleave", () => {
    depth = Math.max(0, depth - 1);
    if (!depth) panel.classList.remove("dropping");
  });
  panel.addEventListener("drop", (ev) => {
    if (!hasFiles(ev)) return;
    ev.preventDefault();
    depth = 0;
    panel.classList.remove("dropping");
    const f = imageFile(ev.dataTransfer.files);
    if (f) attach(f);
    else showError("That is not an image.");
  });
}

export function setup() {
  $("attach-button").setAttribute("aria-haspopup", "menu");
  $("attach-button").setAttribute("aria-expanded", "false");
  $("attach-button").addEventListener("click", () => {
    // on a phone: the camera or the library; elsewhere the file picker at once
    if (touch.matches || phone.matches) { if ($("attach-menu").hidden) openMenu(); else closeMenu(); }
    else pick($("attach-file"));
  });
  $("attach-camera").addEventListener("click", () => pick($("attach-capture")));
  $("attach-library").addEventListener("click", () => pick($("attach-file")));
  $("attach-menu").addEventListener("keydown", (ev) => {
    if (ev.key === "Escape") { closeMenu(); $("attach-button").focus(); }
    if (ev.key === "ArrowDown" || ev.key === "ArrowUp") {
      ev.preventDefault();
      const items = [$("attach-camera"), $("attach-library")];
      items[(items.indexOf(document.activeElement) + 1) % 2].focus();
    }
  });
  document.addEventListener("pointerdown", (ev) => { if (!ev.target.closest(".attach-wrap")) closeMenu(); });
  for (const id of ["attach-file", "attach-capture"]) {
    $(id).addEventListener("change", (ev) => attach(imageFile(ev.target.files)));
  }
  $("ask-input").addEventListener("paste", (ev) => {
    const items = [...((ev.clipboardData && ev.clipboardData.items) || [])];
    const item = items.find((i) => i.kind === "file" && i.type.startsWith("image/"));
    if (!item) return;
    ev.preventDefault();
    attach(item.getAsFile());
  });
  $("attach-remove").addEventListener("click", remove);
  setupDrop();
  on("voice", onVoice);
}

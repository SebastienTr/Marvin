// SPDX-License-Identifier: MIT
// The first-run setup (docs/ui.md "Setting Marvin up"; GET /api/setup and POST /api/setup/{step}, contract C2): a
// full screen of its own, one step at a time, for someone who has never set up a robot. Each step says in plain words
// what it is for, what the host found, and how to fix what is missing (a command to copy, or an action here); anything
// the owner does not have yet can be skipped. The checks run again every few seconds while the screen is open, so the
// robot or the MR60BHA2 kit turn green by themselves when they say hello. It opens by itself on the first visit until
// the setup is done, or put off with "Finish later" (kept in this browser); Marvin > Preferences opens it again.

import { $, app, el, api, post, on, emit, local, toast } from "./core.js";
import { openCalibration } from "./ears.js";

const LATER_KEY = "marvin.setup.later";
const EARS_OFFERED = "marvin.ears.offered";
const POLL_MS = 4000;
const LANGUAGES = [["fr", "Français"], ["en", "English"], ["de", "Deutsch"], ["es", "Español"], ["it", "Italiano"], ["nl", "Nederlands"], ["pt", "Português"]];
const MAX_LANGUAGES = 4;

/** What each step is, for a beginner: its group, its title, why it matters. */
const STEPS = {
  host: {
    group: "This computer", title: "Marvin’s host",
    about: "Marvin’s brain runs on this computer: it keeps what Marvin remembers in a small database here, and nothing leaves it.",
  },
  model: {
    group: "This computer", title: "The language model",
    about: "Marvin thinks with a language model that runs on this computer, through Ollama. No cloud, no account.",
  },
  voice: {
    group: "This computer", title: "Ears and voice",
    about: "Speech recognition and Marvin’s voice run here too. The first start downloads their models, a few gigabytes.",
  },
  robot: {
    group: "The robot", title: "The robot",
    about: "The little robot on your desk: its face, camera, microphone, speaker and radar. Plug it in with USB-C: it joins your Wi-Fi and finds this computer by itself, usually within fifteen seconds.",
    waiting: "Waiting for the robot to say hello…",
  },
  mr60: {
    group: "The robot", title: "Heart and breathing (optional)",
    about: "An MR60BHA2 kit beside Marvin reads your heart rate and breathing while you sit still. It is optional: skip this if you do not have one.",
    waiting: "Waiting for the kit to say hello…",
  },
  speaker: {
    group: "The robot", title: "The speaker",
    about: "Marvin plays a short sound on its speaker. Listen, then say whether you heard it.",
  },
  voiceprint: {
    group: "You", title: "Your voice",
    about: "Read three short sentences: Marvin learns your voice, so it knows when you are the one speaking. Your voiceprint stays on this computer.",
  },
  owner: {
    group: "You", title: "About you",
    about: "Marvin greets you by name when you sit down after a while away, in the language you last spoke.",
  },
  quiet_hours: {
    group: "You", title: "Quiet hours",
    about: "When Marvin should stay quiet: no break reminders, nothing said on its own. It still answers when you speak to it.",
  },
};
const ORDER = Object.keys(STEPS);
const STATE_WORDS = { ok: "Ready", missing: "Needs you", skipped: "Skipped", checking: "Checking…" };
const NOT_SKIPPABLE = new Set(["host"]);
const COMMAND = /^(\.\/marvin|ollama |brew |pip |docker |python3? |pio )/;

const s = { status: null, index: 0, finished: false, timer: 0, busy: false, played: false, available: true };

const visible = () => app.view === "setup" && !document.hidden;
const stepOf = (id) => (s.status ? s.status.steps.find((x) => x.id === id) : null);
const remaining = () => (s.status ? s.status.steps.filter((x) => x.state === "missing") : []);

/** Whether the setup should open by itself: not done, and not put off in this browser. */
export async function wanted() {
  try {
    s.status = await api("/api/setup");
  } catch (e) {
    s.available = false;               // a host without the setup (or not answering): Home
    return false;
  }
  renderNudge();
  return !s.status.done && !local.get(LATER_KEY, false);
}

/** Home's line while the setup waits for the owner (after "Finish later"). */
function renderNudge() {
  const n = $("setup-nudge");
  const left = remaining().length;
  n.hidden = !s.status || s.status.done || !left;
  if (!n.hidden) $("setup-nudge-text").textContent = `${left} ${left === 1 ? "step still needs" : "steps still need"} you: ${remaining().map((x) => STEPS[x.id].title.replace(" (optional)", "")).join(", ")}.`;
}

async function refresh() {
  try {
    s.status = await api("/api/setup");
    s.available = true;
  } catch (e) {
    s.available = false;
  }
  emit("setup", s.status);
  renderNudge();
  if (visible()) renderStatus();
}

function schedule() {
  clearTimeout(s.timer);
  if (visible()) s.timer = setTimeout(() => refresh().then(schedule), POLL_MS);
}

async function act(body, { quiet = false } = {}) {
  const id = ORDER[s.index];
  const err = $("setup-error");
  err.hidden = true;
  s.busy = true;
  renderActions();
  try {
    s.status = await post(`/api/setup/${id}`, body);
    emit("setup", s.status);
    renderNudge();
    return true;
  } catch (e) {
    err.textContent = e.message;
    err.hidden = false;
    if (!quiet) toast(e.message);
    return false;
  } finally {
    s.busy = false;
    renderStatus();
  }
}

// ------------------------------------------------------------------ the list of steps

function renderList() {
  const list = $("setup-list");
  let group = "";
  const items = [];
  ORDER.forEach((id, i) => {
    const meta = STEPS[id];
    if (meta.group !== group) {
      group = meta.group;
      items.push(el("li", "setup-list-group label", group));
    }
    const st = stepOf(id);
    const li = el("li");
    const b = el("button", "setup-item");
    b.type = "button";
    b.dataset.state = st ? st.state : "checking";
    if (i === s.index && !s.finished) b.setAttribute("aria-current", "step");
    b.append(el("span", "setup-mark", ""), el("span", "setup-item-title", meta.title.replace(" (optional)", "")),
      el("span", "sr", `: ${STATE_WORDS[b.dataset.state]}`));
    b.querySelector(".setup-mark").setAttribute("aria-hidden", "true");
    b.addEventListener("click", () => go(i));
    li.append(b);
    items.push(li);
  });
  list.replaceChildren(...items);
  const done = s.status ? s.status.steps.filter((x) => x.state !== "missing").length : 0;
  $("setup-progress-label").textContent = s.status ? `${done} of ${ORDER.length} ready or skipped` : "Checking…";
  $("setup-progress").setAttribute("aria-valuenow", String(done));
  $("setup-progress-fill").style.width = `${(done / ORDER.length) * 100}%`;
}

// ------------------------------------------------------------------ one step

function go(i, focus = true) {
  s.finished = i >= ORDER.length;
  s.index = Math.max(0, Math.min(ORDER.length - 1, i));
  s.played = false;
  $("setup-error").hidden = true;
  renderCard();
  if (focus) $("setup-step-title").focus({ preventScroll: true });
  if (focus && window.matchMedia("(max-width: 900px)").matches) $("setup-card").scrollIntoView({ block: "start" });
}

function renderCard() {
  const card = $("setup-card");
  card.classList.toggle("finished", s.finished);
  const body = $("setup-body");
  body.replaceChildren();
  if (s.finished) {
    renderFinished();
    renderList();
    return;
  }
  const id = ORDER[s.index];
  const meta = STEPS[id];
  $("setup-group").textContent = `${meta.group} · step ${s.index + 1} of ${ORDER.length}`;
  $("setup-step-title").textContent = meta.title;
  $("setup-about").textContent = meta.about;
  $("setup-status").hidden = false;
  if (id === "speaker") body.append(speakerBody());
  if (id === "voiceprint") body.append(voiceprintBody());
  if (id === "owner") ownerBody(body);
  if (id === "quiet_hours") quietBody(body);
  renderStatus();
}

function renderStatus() {
  renderList();
  if (s.finished) { renderFinished(); return; }
  const id = ORDER[s.index];
  const st = stepOf(id);
  const box = $("setup-status");
  const state = !s.available ? "missing" : st ? st.state : "checking";
  box.dataset.state = state;
  const waiting = state === "missing" && STEPS[id].waiting;
  box.classList.toggle("waiting", !!waiting);
  $("setup-state").textContent = !s.available ? "Marvin’s host does not answer" : waiting ? STEPS[id].waiting : STATE_WORDS[state];
  $("setup-detail").textContent = !s.available ? "Is it running? ./marvin status says." : st ? st.detail : "";
  const fix = st && st.state === "missing" ? st.fix : null;
  $("setup-fix").hidden = !fix;
  $("setup-fix-text").textContent = fix || "";
  // a command to copy, or an action in words
  const command = !!fix && COMMAND.test(fix);
  $("setup-fix").classList.toggle("words", !command);
  $("setup-copy").hidden = !command;
  if (id === "speaker") renderSpeaker(st);
  renderActions();
}

function renderActions() {
  if (s.finished) return;
  const id = ORDER[s.index];
  const st = stepOf(id);
  const state = st ? st.state : "checking";
  $("setup-back").hidden = s.index === 0;
  $("setup-skip").hidden = state !== "missing" || NOT_SKIPPABLE.has(id);
  // the forms have their own button; the checks can be run again
  $("setup-check").hidden = state === "ok" || ["owner", "quiet_hours", "speaker"].includes(id);
  const next = $("setup-next");
  next.textContent = s.index === ORDER.length - 1 ? "Finish" : "Continue";
  next.classList.toggle("primary", state !== "missing");
  for (const b of [$("setup-skip"), $("setup-check"), next]) b.disabled = s.busy;
}

// ------------------------------------------------------------------ the steps with something to do

function speakerBody() {
  const wrap = el("div", "setup-speaker");
  const play = el("button", "primary", "Play the test sound");
  play.type = "button";
  play.id = "setup-play";
  play.addEventListener("click", async () => {
    play.disabled = true;
    if (await act({ play: true })) {
      const st = stepOf("speaker");
      s.played = !st.fix;
      renderSpeaker(st);
    }
    play.disabled = false;
  });
  const ask = el("div", "setup-heard");
  ask.id = "setup-heard";
  ask.hidden = true;
  ask.append(el("p", null, "Did you hear it?"));
  const yes = el("button", "primary", "Yes, I heard it");
  const no = el("button", null, "No");
  yes.type = no.type = "button";
  yes.addEventListener("click", () => act({ heard: true }).then((ok) => { if (ok) { s.played = false; renderStatus(); } }));
  no.addEventListener("click", () => act({ heard: false }).then((ok) => { if (ok) { s.played = false; renderStatus(); } }));
  const row = el("div", "row-actions");
  row.append(yes, no);
  ask.append(row);
  wrap.append(play, ask);
  return wrap;
}

function renderSpeaker(st) {
  const ask = $("setup-heard");
  if (!ask) return;
  ask.hidden = !(s.played && st && st.state !== "ok");
  const play = $("setup-play");
  if (play) {
    play.textContent = st && st.state === "ok" ? "Play it again" : s.played ? "Play it again" : "Play the test sound";
    play.classList.toggle("primary", !(st && st.state === "ok") && !s.played);
  }
}

function voiceprintBody() {
  const wrap = el("div", "setup-voiceprint");
  const b = el("button", "primary", "Calibrate my voice");
  b.type = "button";
  b.addEventListener("click", async () => {
    await openCalibration();
    const d = $("dialog");
    const closed = () => { d.removeEventListener("close", closed); refresh(); };
    d.addEventListener("close", closed);
  });
  const voiceOff = !app.voice || app.voice.state !== "on";
  wrap.append(b, el("p", "hint", voiceOff
    ? "The voice must be on: turn it on in Talk, then come back. It takes a quiet minute."
    : "A quiet minute: Marvin measures the room, then you read three sentences, one at a time."));
  return wrap;
}

function ownerBody(body) {
  const form = el("form", "form setup-form");
  form.noValidate = true;
  const name = el("input");
  name.type = "text";
  name.id = "setup-name";
  name.maxLength = 40;
  name.autocomplete = "given-name";
  name.spellcheck = false;
  const owner = (app.settings && app.settings.owner) || { name: "", languages: [] };
  name.value = owner.name || "";
  const f1 = el("div", "field");
  const l1 = el("label", null, "Your name");
  l1.htmlFor = "setup-name";
  f1.append(l1, name, el("p", "hint", "As Marvin should say it: “Bonjour Sébastien”."));
  const f2 = el("fieldset", "field");
  f2.append(el("legend", null, "The languages you speak to Marvin"));
  const chips = languageChips("setup-lang", owner.languages || []);
  f2.append(chips, el("p", "hint", "Up to four. Pick the one you use most first."));
  const f3 = el("div", "field");
  const greet = el("label", "check");
  const box = el("input");
  box.type = "checkbox";
  box.id = "setup-greet";
  box.checked = !app.voiceSettings || app.voiceSettings.greet_on_sit !== false;
  greet.append(box, el("span", null, "Greet me by name when I sit down"));
  f3.append(greet);
  const save = el("button", "primary", "Save");
  save.type = "submit";
  const actions = el("div", "actions");
  actions.append(save);
  form.append(f1, f2, f3, actions);
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const languages = chosenLanguages(chips);
    if (!name.value.trim()) { showError("Your name, please: Marvin greets you with it."); name.focus(); return; }
    if (!languages.length) { showError("Pick at least one language."); return; }
    if (await act({ name: name.value.trim(), languages })) {
      emit("owner-saved");
      await saveGreeting(box.checked);
      toast(`Nice to meet you, ${name.value.trim()}.`);
    }
  });
  body.append(form);
}

/** The greeting switch is a voice setting (greet_on_sit): saved only when it changes, since saving restarts the voice. */
async function saveGreeting(on) {
  try {
    const v = app.voiceSettings || (await api("/api/voice")).settings;
    if (!v || v.greet_on_sit === on) return;
    const r = await post("/api/voice/settings", { greet_on_sit: on });
    app.voiceSettings = r.settings;
    emit("voice", r.voice);
  } catch (e) { /* no voice on this host: the greeting waits for it */ }
}

function showError(text) {
  const err = $("setup-error");
  err.textContent = text;
  err.hidden = false;
}

/** Language chips that keep the order they were picked in (the first: the one used most). */
export function languageChips(name, chosen) {
  const wrap = el("div", "chips lang-chips");
  const order = [...chosen];
  const sync = () => {
    for (const input of wrap.querySelectorAll("input")) {
      const i = order.indexOf(input.value);
      input.checked = i >= 0;
      input.disabled = i < 0 && order.length >= MAX_LANGUAGES;
      input.nextElementSibling.querySelector(".lang-rank").textContent = i >= 0 ? String(i + 1) : "";
    }
    wrap.dataset.order = order.join(",");
  };
  for (const [code, label] of LANGUAGES) {
    const l = el("label");
    const input = el("input");
    input.type = "checkbox";
    input.name = name;
    input.value = code;
    const span = el("span", null, label);
    span.append(el("b", "lang-rank"));
    l.append(input, span);
    input.addEventListener("change", () => {
      const i = order.indexOf(code);
      if (input.checked && i < 0) order.push(code);
      if (!input.checked && i >= 0) order.splice(i, 1);
      sync();
    });
    wrap.append(l);
  }
  sync();
  return wrap;
}

export const chosenLanguages = (chips) => (chips.dataset.order ? chips.dataset.order.split(",") : []);

function quietBody(body) {
  const q = (app.settings && app.settings.quiet_hours) || { enabled: false, start: "22:00", end: "07:00" };
  const form = el("form", "form setup-form");
  const f = el("fieldset", "field");
  f.append(el("legend", null, "Quiet between"));
  const row = el("div", "inline");
  const start = el("input");
  start.type = "time";
  start.value = q.start;
  start.setAttribute("aria-label", "Quiet hours start");
  const end = el("input");
  end.type = "time";
  end.value = q.end;
  end.setAttribute("aria-label", "Quiet hours end");
  row.append(start, el("span", "muted", "and"), end);
  f.append(row, el("p", "hint", "For example from 22:00 to 07:00: the night, across midnight."));
  const actions = el("div", "actions");
  const none = el("button", null, "No quiet hours");
  none.type = "button";
  const save = el("button", "primary", "Save quiet hours");
  save.type = "submit";
  none.addEventListener("click", () => act({ enabled: false, start: start.value || "22:00", end: end.value || "07:00" })
    .then((ok) => { if (ok) refreshSettings(); }));
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    act({ enabled: true, start: start.value || "22:00", end: end.value || "07:00" }).then((ok) => { if (ok) refreshSettings(); });
  });
  actions.append(none, save);
  form.append(f, actions);
  body.append(form);
}

async function refreshSettings() {
  try {
    const r = await api("/api/settings");
    emit("settings", r.settings);
  } catch (e) { /* the stream says it too */ }
}

// ------------------------------------------------------------------ the end

function renderFinished() {
  const left = remaining();
  $("setup-group").textContent = left.length ? "Almost there" : "All set";
  $("setup-step-title").textContent = left.length ? `${left.length} ${left.length === 1 ? "step still needs" : "steps still need"} you.` : "Marvin is ready.";
  $("setup-about").textContent = left.length
    ? "Marvin works without them, with less. Pick one to finish it, or come back later from Marvin > Preferences."
    : "Sit down in front of Marvin: after a while away, it says hello. Talk to it by saying “Marvin, …”.";
  $("setup-status").hidden = true;
  $("setup-fix").hidden = true;
  const body = $("setup-body");
  body.replaceChildren();
  const list = el("ul", "setup-summary");
  for (const st of s.status ? s.status.steps : []) {
    const li = el("li");
    li.dataset.state = st.state;
    li.append(el("span", "setup-mark", ""), el("b", null, STEPS[st.id].title.replace(" (optional)", "")), el("span", "muted", st.detail));
    li.querySelector(".setup-mark").setAttribute("aria-hidden", "true");
    if (st.state === "missing") {
      const b = el("button", "small-button", "Finish it");
      b.type = "button";
      b.addEventListener("click", () => go(ORDER.indexOf(st.id)));
      li.append(b);
    }
    list.append(li);
  }
  body.append(list);
  $("setup-back").hidden = false;
  $("setup-skip").hidden = true;
  $("setup-check").hidden = true;
  const next = $("setup-next");
  next.textContent = "Go to Home";
  next.classList.add("primary");
  next.disabled = false;
}

function leave() {
  if (!s.status || !s.status.done) local.set(LATER_KEY, true);
  location.hash = "#home";
}

// ------------------------------------------------------------------ setup

export function setup() {
  $("setup-back").addEventListener("click", () => go(s.finished ? ORDER.length - 1 : s.index - 1));
  $("setup-next").addEventListener("click", () => {
    if (s.finished) { leave(); return; }
    go(s.index + 1);
  });
  $("setup-skip").addEventListener("click", async () => {
    if (await act({ skip: true })) go(s.index + 1);
  });
  $("setup-check").addEventListener("click", async () => {
    if (await act({}, { quiet: true })) toast(STATE_WORDS[stepOf(ORDER[s.index]).state]);
  });
  $("setup-later").addEventListener("click", () => { local.set(LATER_KEY, true); location.hash = "#home"; });
  $("setup-copy").addEventListener("click", async () => {
    const text = $("setup-fix-text").textContent;
    try {
      await navigator.clipboard.writeText(text);
      toast("Copied.");
    } catch (e) {
      // no clipboard here (http on a phone): select it, the owner copies it
      const r = document.createRange();
      r.selectNodeContents($("setup-fix-text"));
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
      toast("Selected: copy it with your keyboard or a long press.");
    }
  });
  on("view", async ({ view }) => {
    if (view !== "setup") { clearTimeout(s.timer); return; }
    // the setup offers the voice calibration itself: not the dialog that opens by itself on the first run
    try { window.localStorage.setItem(EARS_OFFERED, "1"); } catch (e) { /* not kept */ }
    if (!s.status) await refresh();
    // open at the first step that needs the owner
    const first = s.status ? s.status.steps.findIndex((x) => x.state === "missing") : 0;
    go(first < 0 ? ORDER.length : first, false);
    refresh().then(schedule);
  });
  on("settings", () => {
    if (!visible() || s.finished) return;
    const id = ORDER[s.index];
    if (id === "owner" || id === "quiet_hours") refresh();
  });
  document.addEventListener("visibilitychange", () => { if (visible()) refresh().then(schedule); });
}

/** The setup's status as last read (for Marvin > Overview), or null. */
export const status = () => s.status;

// SPDX-License-Identifier: MIT
// Memory's dialogs: the profile with its versions, their differences, the owner's edit and a restore; the export;
// "forget everything" (a code, then the phrase typed); the raw log; memory's settings. Opened from the Memory screen.

import { $, el, api, post, emit, whenLabel, fmtTime, openDialog, closeDialog, toast, plural, timeEl } from "./core.js";
import * as worker from "./worker.js";

function button(text, cls, fn) {
  const b = el("button", cls, text);
  b.type = "button";
  if (fn) b.addEventListener("click", fn);
  return b;
}

function errorLine() {
  const p = el("p", "form-error");
  p.hidden = true;
  p.setAttribute("role", "alert");
  return p;
}

// ------------------------------------------------------------------ profile

const clean = (l) => l.replace(/^[-*]\s*/, "").trim();

/** The profile's lines, each marked when it is the owner's (kept by every rewrite). */
export function profileLines(p) {
  const kept = new Set((p.kept_lines || []).map(clean));
  return p.content.split("\n").map(clean).filter(Boolean).map((text) => ({ text, kept: kept.has(text) }));
}

export async function openProfile() {
  const body = openDialog("A few things about you.", { label: "Profile", wide: true });
  body.append(el("p", null, "Loading…"));
  let r;
  try { r = await api("/api/memory/profile?limit=30"); } catch (e) {
    body.replaceChildren(el("p", null, `Your profile cannot be read: ${e.message}`));
    return;
  }
  renderProfile(r);
}

function renderProfile(r) {
  const body = $("dialog-body");
  const p = r.current;
  $("dialog-label").textContent = p ? `Profile · Version ${p.id}` : "Profile";
  $("dialog-title").textContent = "A few things about you.";
  body.replaceChildren();
  body.append(el("p", null, "Marvin reads this at the start of every conversation. It rewrites it at night from what it learned; the lines you write are yours, and every rewrite keeps them."));
  if (p && p.content.trim()) {
    const ul = el("ul", "profile-lines");
    for (const { text, kept } of profileLines(p)) ul.append(el("li", kept ? "kept" : "", text));
    body.append(ul, el("p", "small", `${p.author === "owner" ? "Written by you" : "Written by Marvin"} · ${whenLabel(p.created_at)} · ${p.tokens} tokens${p.rationale && p.author !== "owner" ? ` · ${p.rationale}` : ""}`));
  } else {
    body.append(el("p", "empty-line", "Nothing written yet."));
  }
  const edit = button(p && p.content.trim() ? "Edit" : "Write it yourself", "small-button", () => editProfile(r));
  const flags = el("div", "fact-flags");
  flags.append(edit);
  body.append(flags);

  const older = r.versions.filter((v) => !p || v.id !== p.id);
  body.append(el("h3", "gap-top", "Versions"));
  if (!r.versions.length) body.append(el("p", "small", "No version yet."));
  const ol = el("ol", "versions-list");
  for (const v of r.versions) {
    const li = el("li", "version");
    const head = el("div", "version-head");
    head.append(el("b", null, `Version ${v.id}`), el("span", "small muted", `${v.author === "owner" ? "you" : "Marvin"} · ${whenLabel(v.created_at)}${p && v.id === p.id ? " · in use" : ""}`));
    li.append(head);
    if (v.rationale && v.author !== "owner") li.append(el("p", "small", v.rationale));
    const diff = (v.diff || []).filter((d) => d.op !== " " && d.op !== "=");
    if (diff.length) {
      const ul = el("ul", "diff");
      for (const d of diff) {
        const add = d.op === "+";
        const line = el("li", add ? "add" : "del");
        line.append(el("span", "sr", add ? "Added: " : "Removed: "), el("span", "op", add ? "+" : "−"), clean(d.text));
        ul.append(line);
      }
      li.append(ul);
    } else li.append(el("p", "small", "No change in its lines."));
    if (p && v.id !== p.id) {
      li.append(button("Use this version again", "small-button", async (ev) => {
        ev.target.disabled = true;
        try {
          await post("/api/memory/profile/restore", { version: v.id });
          toast(`Version ${v.id} is in use again, as a new version.`);
          emit("fact-changed", { what: "profile" });
          renderProfile(await api("/api/memory/profile?limit=30"));
        } catch (e) { toast(`Not restored: ${e.message}`); ev.target.disabled = false; }
      }));
    }
    ol.append(li);
  }
  body.append(ol);
  if (older.length) body.append(el("p", "small", "Using an older version makes a new one: nothing in the history is lost."));
}

function editProfile(r) {
  const p = r.current;
  const body = $("dialog-body");
  $("dialog-title").textContent = "In your words.";
  const before = p ? profileLines(p) : [];
  const form = el("form");
  form.noValidate = true;
  const lab = el("label", null, "One line per thing Marvin should know about you");
  lab.htmlFor = "profile-text";
  const ta = el("textarea", "tall");
  ta.id = "profile-text";
  ta.maxLength = 4000;
  ta.value = before.map((l) => l.text).join("\n");
  const err = errorLine();
  const actions = el("div", "actions");
  const ok = el("button", "primary", "Save");
  ok.type = "submit";
  actions.append(button("Cancel", null, () => renderProfile(r)), ok);
  form.append(lab, ta, el("p", "small", "Lines you write or change become yours: the nightly rewrite keeps them. Removing a line removes it from the profile."), err, actions);
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const lines = ta.value.split("\n").map(clean).filter(Boolean);
    const was = new Map(before.map((l) => [l.text, l.kept]));
    const kept = lines.filter((l) => !was.has(l) || was.get(l));
    ok.disabled = true;
    try {
      await post("/api/memory/profile", { content: lines.map((l) => `- ${l}`).join("\n"), kept_lines: kept });
      toast("Profile saved. Marvin uses it from the next question.");
      emit("fact-changed", { what: "profile" });
      renderProfile(await api("/api/memory/profile?limit=30"));
    } catch (e) {
      err.textContent = `Not saved: ${e.message}`;
      err.hidden = false;
      ok.disabled = false;
    }
  });
  body.replaceChildren(form);
  ta.focus();
}

// ------------------------------------------------------------------ export

export function openExport() {
  const body = openDialog("Everything, in a file.", { label: "Memory · export" });
  body.append(el("p", null, "Every fact with its sources and versions, the profile and its history, the days and weeks, and the raw log: the whole of Marvin’s memory, as it is now."));
  const list = el("div", "export-choices");
  for (const [fmt, title, text] of [
    ["json", "JSON", "Complete and machine-readable: every table, every field."],
    ["markdown", "Markdown", "Readable: facts, profile and summaries, as a document."],
  ]) {
    const a = el("a", "button export-choice");
    a.href = `/api/memory/export?format=${fmt}`;
    a.setAttribute("download", "");
    a.append(el("b", null, title), el("span", "small muted", text));
    a.addEventListener("click", () => setTimeout(() => toast("Exported. The file stays on this device."), 300));
    list.append(a);
  }
  body.append(list, el("p", "small", "Sensitive facts are included: keep the file somewhere private."));
}

// ------------------------------------------------------------------ forget everything

export async function openForgetEverything() {
  const body = openDialog("Forget everything?", { label: "Memory · forget everything" });
  let counts = null;
  try { counts = await api("/api/memory"); } catch (e) { /* the numbers stay unknown */ }
  const w = counts && counts.worker;
  body.append(
    el("p", null, `Every fact${w ? ` (${w.facts})` : ""}, the profile and its versions, the days and weeks, and the log Marvin learns from${w ? ` (${plural(w.events, "moment", "moments")})` : ""}: all of it, at once and for good.`),
    el("p", null, "Your conversations stay in History, and the settings stay as they are. From the next moment on, Marvin starts learning again, unless you switch its sources off."),
  );
  const ex = el("a", "quiet-link small", "Export first ↗");
  ex.href = "/api/memory/export?format=json";
  ex.setAttribute("download", "");
  body.append(ex);
  const err = errorLine();
  const actions = el("div", "actions");
  const go = button("Continue", "danger", async () => {
    go.disabled = true;
    try { second(await post("/api/memory/forget-everything", {})); } catch (e) {
      err.textContent = e.message; err.hidden = false; go.disabled = false;
    }
  });
  actions.append(button("Keep my memory", null, () => closeDialog()), go);
  body.append(err, actions);
}

function second(p) {
  const body = $("dialog-body");
  $("dialog-title").textContent = "Are you sure?";
  const form = el("form");
  form.noValidate = true;
  const lab = el("label");
  lab.htmlFor = "forget-phrase";
  lab.append("To confirm, type ", el("b", null, p.phrase));
  const input = el("input");
  input.type = "text";
  input.id = "forget-phrase";
  input.autocomplete = "off";
  input.spellcheck = false;
  input.setAttribute("autocapitalize", "off");
  const err = errorLine();
  const actions = el("div", "actions");
  const ok = el("button", "primary danger-primary", "Forget everything");
  ok.type = "submit";
  ok.disabled = true;
  input.addEventListener("input", () => { ok.disabled = input.value.trim().toLowerCase() !== p.phrase; });
  actions.append(button("Keep my memory", null, async () => {
    closeDialog();
    post("/api/memory/forget/cancel", { confirm: p.confirm }).catch(() => {});
  }), ok);
  form.append(el("p", null, `This request expires at ${fmtTime(p.expires_at)}.`), lab, input, err, actions);
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    if (ok.disabled) return;
    ok.disabled = true;
    try {
      const r = await post("/api/memory/forget-everything", { confirm: p.confirm, phrase: input.value.trim().toLowerCase() });
      closeDialog();
      toast(`Forgotten: ${plural(r.forgotten.facts, "fact", "facts")} and ${plural(r.forgotten.events, "moment", "moments")}.`);
      emit("fact-changed", { what: "all" });
    } catch (e) {
      err.textContent = /expired|unknown/i.test(e.message) ? "This request expired. Close and start again." : e.message;
      err.hidden = false;
    }
  });
  body.replaceChildren(form);
  input.focus();
}

// ------------------------------------------------------------------ raw log

const LOG_SOURCES = { conversation: "Conversation", brain: "Marvin", owner: "You", presence: "Presence", app: "App" };

export function openLog() {
  const body = openDialog("Everything memory was told.", { label: "Memory · raw log", wide: true });
  body.append(el("p", null, "The log memory learns from, newest first, as it was written. Facts point back to it; forgetting removes lines from it."));
  const search = el("label", "search");
  const input = el("input");
  input.type = "search";
  input.placeholder = "Search the log…";
  input.maxLength = 200;
  search.append(el("span", "small muted", "Find"), input);
  const list = el("ol", "log mem-log");
  const status = el("p", "small muted");
  status.setAttribute("role", "status");
  const more = button("Older", "small-button");
  more.hidden = true;
  const moreRow = el("div", "more-row");
  moreRow.append(more);
  body.append(search, status, list, moreRow);
  let before = 0, seq = 0, t = 0;
  const load = async (append) => {
    const n = ++seq;
    let r;
    try {
      r = await api(`/api/memory/log?limit=100${before ? `&before=${before}` : ""}${input.value.trim() ? `&q=${encodeURIComponent(input.value.trim())}` : ""}`);
    } catch (e) { status.textContent = `Cannot be read: ${e.message}`; return; }
    if (n !== seq) return;
    const items = r.events.map((e) => {
      const li = el("li");
      const d = new Date(e.ts * 1000);
      li.append(timeEl(e.ts, `${d.toLocaleDateString("en-GB", { day: "numeric", month: "short" })} ${fmtTime(e.ts)}`),
        el("span", "src", LOG_SOURCES[e.source] || e.source),
        el("span", `text${e.sensitivity === "sensitive" ? " sensitive" : ""}`, e.text || "(forgotten)"));
      return li;
    });
    if (append) list.append(...items); else list.replaceChildren(...items);
    before = r.next_before || 0;
    more.hidden = !r.next_before;
    status.textContent = list.children.length ? "" : input.value.trim() ? "Nothing matches." : "The log is empty.";
  };
  more.addEventListener("click", () => load(true));
  input.addEventListener("input", () => { clearTimeout(t); t = setTimeout(() => { before = 0; load(false); }, 250); });
  load(false);
}

// ------------------------------------------------------------------ settings

export async function openSettings() {
  const body = openDialog("How memory works for you.", { label: "Memory · settings" });
  body.append(el("p", null, "Loading…"));
  let r;
  try { r = await api("/api/memory/settings"); } catch (e) {
    body.replaceChildren(el("p", null, `The settings cannot be read: ${e.message}`));
    return;
  }
  const s = r.settings;
  const form = el("form", "form");
  form.noValidate = true;
  const num = (id, label, value, min, max, unit, hint) => {
    const f = el("div", "field");
    const l = el("label", null, label);
    l.htmlFor = id;
    const i = el("input");
    i.type = "number"; i.id = id; i.min = min; i.max = max; i.step = 1; i.value = value; i.required = true;
    i.inputMode = "numeric";
    const row = el("div", "inline");
    row.append(i, el("span", "muted", unit));
    f.append(l, row, el("p", "hint", hint));
    return f;
  };
  const work = el("label", "check");
  const wb = el("input");
  wb.type = "checkbox"; wb.checked = s.worker;
  work.append(wb, el("span", null, "Let memory consolidate on its own"));
  const workField = el("div", "field");
  workField.append(work, el("p", "hint", "Off: nothing is read or summarised until you choose “Consolidate now”. What happens is still written to the log, if its source is on."));
  form.append(workField,
    num("ms-idle", "After you leave", s.idle_minutes, 1, 240, "minutes away, read what is waiting", "Memory waits for you to be away, and gives way to the voice as soon as you talk."),
    num("ms-night", "Nightly pass", s.night_hour, 0, 23, "o’clock", "The days, weeks, profile and fading are done once a night, at this hour or as soon as you are away after it."),
    num("ms-retention", "Keep what Marvin noticed", s.retention_days, 7, 36500, "days", "Older moments are removed once their day is written, unless a fact comes from them. Conversations are never removed by this."));
  const models = el("p", "small");
  const a = el("a", null, "Marvin › Voice & models");
  a.href = "#marvin/voice";
  a.addEventListener("click", () => closeDialog());
  models.append("Memory’s models are in ", a, ".");
  const err = errorLine();
  const actions = el("div", "actions");
  const ok = el("button", "primary", "Save");
  ok.type = "submit";
  actions.append(button("Cancel", null, () => closeDialog()), ok);
  form.append(models, err, actions);
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    for (const i of form.querySelectorAll("input[type=number]")) if (!i.reportValidity()) return;
    ok.disabled = true;
    try {
      await post("/api/memory/settings", {
        worker: wb.checked, idle_minutes: Number($("ms-idle").value), night_hour: Number($("ms-night").value),
        retention_days: Number($("ms-retention").value),
      });
      closeDialog();
      toast("Memory settings saved.");
      worker.load();
    } catch (e) {
      err.textContent = `Not saved: ${e.message}`;
      err.hidden = false;
      ok.disabled = false;
    }
  });
  body.replaceChildren(form);
}

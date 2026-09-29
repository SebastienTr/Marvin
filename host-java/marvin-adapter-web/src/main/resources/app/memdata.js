// SPDX-License-Identifier: MIT
// Facts as the app shows them: where each one comes from, and the "Source & edit" dialog (GET
// /api/memory/facts/{id}): the source quoted and dated with a link to its conversation, the correction, pin,
// keep, archive, and forgetting as a deliberate second choice. Shared by Home, Talk and Memory.

import { $, api, post, el, emit, whenLabel, openDialog, closeDialog, toast, plural } from "./core.js";

const SOURCE_WORDS = {
  conversation: "From your conversation", owner: "You told Marvin", brain: "From what Marvin noticed",
  presence: "From what Marvin noticed", app: "Written in the app",
};

const STATUS_WORDS = { past: "No longer true", replaced: "Replaced", archived: "Archived" };
const SENSITIVITY = { normal: "", personal: "Personal", sensitive: "Sensitive" };

/** "From your conversation · Today, 11:16" from a fact and, when known, its first source event. */
export function sourceLine(fact, firstSource) {
  let what;
  if (firstSource) what = SOURCE_WORDS[firstSource.source] || `From ${firstSource.source}`;
  else what = fact.origin === "owner" ? "You told Marvin" : fact.origin === "task" ? "From a task" : "From your conversations";
  return `${what} · ${whenLabel(firstSource ? firstSource.ts : fact.learned_at)}`;
}

export function statusPill(fact) {
  if (fact.status && fact.status !== "current") return el("span", "pill", STATUS_WORDS[fact.status] || fact.status);
  if (fact.archived) return el("span", "pill", "Archived");
  if (fact.pinned) return el("span", "pill", "Pinned");
  if (fact.origin === "owner") return el("span", "pill", "Yours");
  if (!fact.reviewed) return el("span", "pill attention-pill", "Suggested");
  return el("span", "pill", "Reviewed");
}

export function sensitivityPill(fact) {
  return SENSITIVITY[fact.sensitivity] ? el("span", "pill quiet-pill", SENSITIVITY[fact.sensitivity]) : null;
}

const isCurrent = (f) => f.status === "current" && !f.archived;
const isSuggested = (f) => isCurrent(f) && f.origin !== "owner" && !f.reviewed;

function changed(what, id) {
  emit("fact-changed", { what, id });
}

/** Pins, keeps or archives a fact; the list and the dialog follow. */
export async function setFlag(f, flag, value) {
  const path = { pinned: "pin", archived: "archive", reviewed: "review" }[flag];
  try {
    await post(`/api/memory/facts/${path}`, { id: f.id, [flag]: value });
  } catch (e) {
    toast(`Not saved: ${e.message}`);
    return false;
  }
  toast({
    pinned: value ? "Pinned: it stays, whatever its age." : "Unpinned.",
    archived: value ? "Archived: Marvin no longer brings it up on its own." : "Back in use.",
    reviewed: value ? "Kept. It is now a reviewed memory." : "Back among the suggestions.",
  }[flag]);
  changed(flag, f.id);
  return true;
}

function button(text, cls, fn) {
  const b = el("button", cls, text);
  b.type = "button";
  b.addEventListener("click", fn);
  return b;
}

/** Opens a fact with its sources, versions and actions. */
export async function openFact(id) {
  const body = openDialog("Remembered, with a source.", { label: "Memory" });
  body.append(el("p", "muted", "Loading…"));
  let d;
  try {
    d = await api(`/api/memory/facts/${encodeURIComponent(id)}`);
  } catch (e) {
    $("dialog-title").textContent = "This memory cannot be read.";
    body.replaceChildren(el("p", null, /not found|404/i.test(e.message) ? "It was forgotten, or replaced by a newer version." : e.message));
    return;
  }
  renderFact(d);
}

function renderFact(d) {
  const f = d.fact;
  const body = $("dialog-body");
  const current = isCurrent(f);
  $("dialog-label").textContent = `Memory · ${isSuggested(f) ? "Suggested" : f.origin === "owner" ? "Yours" : current ? "Reviewed" : STATUS_WORDS[f.status] || "Archived"}`;
  $("dialog-title").textContent = current ? "Remembered, with a source." : f.archived && f.status === "current" ? "Archived, still yours." : "No longer current.";
  body.replaceChildren();

  const tags = el("div", "fact-tags");
  tags.append(statusPill(f));
  const s = sensitivityPill(f);
  if (s) tags.append(s);
  body.append(tags);
  if (isSuggested(f)) body.append(el("p", null, "Marvin learned this on its own. Keep it, correct it, or forget it."));

  // the sources, quoted
  if (d.sources.length) {
    // what the owner said first: a fact usually comes from their own words
    const sources = d.sources.slice().sort((a, b) => (b.kind === "heard") - (a.kind === "heard") || a.ts - b.ts);
    let more = null;
    sources.forEach((src, i) => {
      if (i === 2) {
        more = el("details", "versions");
        more.append(el("summary", null, `${plural(sources.length - 2, "more moment", "more moments")} it was learned from`));
        body.append(more);
      }
      const q = el("blockquote", "source-quote");
      q.append(el("p", null, `« ${src.text} »`));
      const line = el("span", "source", `${SOURCE_WORDS[src.source] || src.source} · ${whenLabel(src.ts)}`);
      q.append(line);
      if (src.conversation) {
        const a = el("a", "small quiet-link", "Open that conversation ↗");
        a.href = "#activity/history";
        a.addEventListener("click", (ev) => {
          ev.preventDefault();
          closeDialog();
          emit("goto-conversation", { day: src.conversation.day, entry: src.conversation.entry });
        });
        q.append(document.createElement("br"), a);
      }
      (more || body).append(q);
    });
  } else {
    body.append(el("p", null, f.origin === "owner" ? "You wrote it yourself." : "Its source is no longer kept."));
  }

  if (current) body.append(editor(d));
  else body.append(el("p", "fact-now", f.statement));

  // flags
  const flags = el("div", "fact-flags");
  if (current) flags.append(button(f.pinned ? "Unpin" : "Pin", "small-button", async () => { if (await setFlag(f, "pinned", !f.pinned)) reopen(f.id); }));
  if (isSuggested(f)) flags.append(button("Keep as reviewed", "small-button", async () => { if (await setFlag(f, "reviewed", true)) reopen(f.id); }));
  if (f.status === "current") {
    flags.append(button(f.archived ? "Bring back" : "Archive", "small-button", async () => { if (await setFlag(f, "archived", !f.archived)) reopen(f.id); }));
  }
  if (flags.children.length) body.append(flags);
  if (current && !f.pinned) body.append(el("p", "small", "Archive: Marvin stops bringing it up on its own, but finds it when you ask. Pin: it never fades."));

  // facts about the fact
  const used = f.use_count ? `Used in ${plural(f.use_count, "answer", "answers")}${f.last_used_at ? `, last ${whenLabel(f.last_used_at)}` : ""}.` : "Not used in an answer yet.";
  body.append(el("p", "small", `${f.when[0].toUpperCase()}${f.when.slice(1)}. ${used} Learned ${whenLabel(f.learned_at)}${f.extracted_by ? ` by ${f.extracted_by}` : ""}; confidence ${Math.round(f.confidence * 100)} %, importance ${f.importance} of 10.`));

  const older = d.versions.filter((v) => v.id !== f.id);
  if (older.length) {
    const det = el("details", "versions");
    det.append(el("summary", null, older.length === 1 ? "One other version" : `${older.length} other versions`));
    const ul = el("ul", "worker-lines");
    for (const v of older) {
      const li = el("li");
      li.append(el("span", null, v.statement), el("span", null, `${STATUS_WORDS[v.status] || "Current"} · ${whenLabel(v.learned_at)}`));
      ul.append(li);
    }
    det.append(ul);
    body.append(det);
  }

  const actions = el("div", "actions");
  actions.append(button("Forget this fact", "danger", () => confirmForget(d)));
  if (current) {
    const save = el("button", "primary", "Save correction");
    save.type = "submit";
    save.setAttribute("form", "fact-form");
    actions.append(save);
  }
  body.append(actions);
}

function editor(d) {
  const f = d.fact;
  const form = el("form", "fact-form");
  form.id = "fact-form";
  form.noValidate = true;
  const lab = el("label", null, "What Marvin should remember");
  lab.htmlFor = "fact-text";
  const ta = el("textarea");
  ta.id = "fact-text";
  ta.maxLength = 500;
  ta.required = true;
  ta.value = f.statement;
  ta.rows = 3;
  const sens = el("fieldset", "field");
  sens.append(el("legend", null, "Sensitivity"));
  const seg = el("div", "segmented");
  for (const [v, t] of [["normal", "Normal"], ["personal", "Personal"], ["sensitive", "Sensitive"]]) {
    const l = el("label"), r = el("input");
    r.type = "radio"; r.name = "fact-sens"; r.value = v; r.checked = f.sensitivity === v;
    l.append(r, el("span", null, t));
    seg.append(l);
  }
  sens.append(seg, el("p", "hint", "Sensitive (health, money, other people’s private matters): never said while Marvin sees someone else in the room."));
  const err = el("p", "form-error");
  err.hidden = true;
  err.setAttribute("role", "alert");
  form.append(lab, ta, el("p", "small", f.valid_to ? `True until ${whenLabel(f.valid_to)}.` : "Applies until you change or forget it."), sens, err);
  ta.addEventListener("input", () => { err.hidden = true; });
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const statement = ta.value.trim();
    const sensitivity = form.querySelector('input[name="fact-sens"]:checked').value;
    if (!statement) {
      err.textContent = "Write what Marvin should remember, or choose Forget.";
      err.hidden = false;
      ta.focus();
      return;
    }
    const body = {};
    if (statement !== f.statement) body.statement = statement;
    if (sensitivity !== f.sensitivity) body.sensitivity = sensitivity;
    if (!Object.keys(body).length) {
      if (isSuggested(f)) { if (await setFlag(f, "reviewed", true)) closeDialog(); return; }
      closeDialog();
      return;
    }
    try {
      const r = await post("/api/memory/facts/edit", { id: f.id, ...body });
      toast("Corrected. The earlier version is kept in its history.");
      changed("edit", r.fact.id);
      closeDialog();
    } catch (e) {
      err.textContent = `Not saved: ${e.message}`;
      err.hidden = false;
    }
  });
  return form;
}

async function reopen(id) {
  try { renderFact(await api(`/api/memory/facts/${encodeURIComponent(id)}`)); } catch (e) { closeDialog(); }
}

/** The second, explicit choice: what forgetting does, and what it does not. */
function confirmForget(d) {
  const f = d.fact;
  const body = $("dialog-body");
  $("dialog-label").textContent = "Forget memory";
  $("dialog-title").textContent = "Let this one go?";
  const others = d.versions.filter((v) => v.id !== f.id).length;
  const q = el("blockquote", "source-quote");
  q.append(el("p", null, f.statement));
  const convo = d.sources.some((s) => s.conversation);
  body.replaceChildren(q,
    el("p", null, `Marvin will no longer use it${others ? `, nor its ${plural(others, "other version", "other versions")}` : ""}, and it leaves your profile at once. This cannot be undone.`),
    el("p", null, convo ? "The conversation where it was said remains in your History." : "What Marvin noticed at the time remains in memory’s log until it is tidied."));
  const err = el("p", "form-error");
  err.hidden = true;
  err.setAttribute("role", "alert");
  const actions = el("div", "actions");
  const keep = button("Keep it", null, () => renderFact(d));
  const go = button("Forget fact", "primary danger-primary", async () => {
    go.disabled = true;
    try {
      const p = await post("/api/memory/facts/forget", { id: f.id });
      await post("/api/memory/facts/forget", { confirm: p.confirm });
      closeDialog();
      toast("Forgotten.");
      changed("forget", f.id);
    } catch (e) {
      err.textContent = `Not forgotten: ${e.message}`;
      err.hidden = false;
      go.disabled = false;
    }
  });
  actions.append(keep, go);
  body.append(err, actions);
  keep.focus();
}

/** "Add a memory": the owner's own fact, stored at once. */
export function openRemember() {
  const body = openDialog("Something to remember.", { label: "Memory · yours" });
  const form = el("form");
  form.noValidate = true;
  const lab = el("label", null, "What Marvin should remember");
  lab.htmlFor = "remember-text";
  const ta = el("textarea");
  ta.id = "remember-text";
  ta.maxLength = 500;
  ta.rows = 3;
  ta.placeholder = "I water the plants on Sundays.";
  const err = el("p", "form-error");
  err.hidden = true;
  err.setAttribute("role", "alert");
  const actions = el("div", "actions");
  const cancel = button("Cancel", null, () => closeDialog());
  const ok = el("button", "primary", "Remember");
  ok.type = "submit";
  actions.append(cancel, ok);
  form.append(lab, ta, el("p", "small", "Stored at once as yours, with this moment as its source. You can change or forget it any time."), err, actions);
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const statement = ta.value.trim();
    if (!statement) { err.textContent = "Write something to remember."; err.hidden = false; ta.focus(); return; }
    ok.disabled = true;
    try {
      const r = await post("/api/memory/facts/remember", { statement });
      closeDialog();
      toast("Remembered.");
      changed("remember", r.fact.id);
    } catch (e) {
      err.textContent = `Not saved: ${e.message}`;
      err.hidden = false;
      ok.disabled = false;
    }
  });
  body.append(form);
  ta.focus();
}

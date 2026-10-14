// SPDX-License-Identifier: MIT
// The owner's approvals (docs/design.md 6.3): a background task that wants to write outside this computer (an email
// draft) waits here with a preview of exactly what it will write. Approve: the task makes the call (a draft in Gmail,
// which the owner sends from Gmail). Reject: it is never made. Marvin never sends an email.
//
// setup(root) renders into `root` (W5 places it: Activity, and a one-line notice in Talk); load() reads the list. It
// listens to the stream's "approval" event (app.js must forward it) and emits "approvals" with the pending ones.

import { el, api, post, on, emit, whenLabel, toast } from "./core.js";

const STATE = { pending: "Waiting for you", approved: "Approved", rejected: "Rejected", expired: "Expired" };
const KIND = { email_draft: "Email draft", tool_call: "Action" };

let root = null;
let list = [];
let failed = "";
let busy = new Set();

/** The approvals waiting for the owner, newest first. */
export function pending() {
  return list.filter((a) => a.state === "pending");
}

function button(text, cls, fn) {
  const b = el("button", cls, text);
  b.type = "button";
  b.addEventListener("click", fn);
  return b;
}

/** Only a link to Gmail itself is shown (the draft Marvin made). */
function gmailLink(url) {
  try {
    const u = new URL(url);
    return u.protocol === "https:" && u.hostname === "mail.google.com";
  } catch (e) {
    return false;
  }
}

function preview(a) {
  const p = a.preview || {};
  const box = el("div", "approval-preview");
  const kv = (label, value) => {
    const row = el("p", "small");
    row.append(el("span", "muted", `${label}: `), el("span", null, value));
    return row;
  };
  box.append(el("p", "label muted", KIND[p.kind] || p.kind || "Action"));
  if (p.to && p.to.length) box.append(kv("To", p.to.join(", ")));
  if (p.subject) box.append(kv(p.kind === "email_draft" ? "Subject" : "Tool", p.subject));
  if (p.body) {
    const pre = el("pre", "approval-body small");
    pre.textContent = p.body;
    box.append(pre);
  }
  if (p.open_url && gmailLink(p.open_url)) {
    const link = el("a", "small", "Open in Gmail");
    link.href = p.open_url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    box.append(link);
  }
  return box;
}

async function decide(a, approve) {
  if (busy.has(a.id)) return;
  busy.add(a.id);
  try {
    let body = {};
    if (!approve) {
      const reason = window.prompt("Why not? (optional, Marvin's task is told)", "");
      if (reason === null) return;
      body = { reason: reason.slice(0, 500) };
    }
    await post(`/api/approvals/${a.id}/${approve ? "approve" : "reject"}`, body);
    toast(approve ? "Approved: Marvin makes it now" : "Rejected: nothing was done");
    await load();
  } catch (e) {
    toast(e.message);
    await load();
  } finally {
    busy.delete(a.id);
  }
}

function item(a) {
  const li = el("li", `approval ${a.state}`);
  const head = el("div", "row");
  head.append(el("h3", null, a.summary || a.tool), el("span", `pill${a.state === "pending" ? " attention-pill" : ""}`,
    STATE[a.state] || a.state));
  li.append(head);
  li.append(el("p", "small muted", `${a.tool} · asked ${whenLabel(a.created)}${a.decided ? ` · ${STATE[a.state].toLowerCase()} ${whenLabel(a.decided)}` : ""}`));
  li.append(preview(a));
  if (a.state === "pending") {
    const actions = el("div", "row-actions");
    actions.append(button("Approve", "primary small-button", () => decide(a, true)),
      button("Reject", "small-button", () => decide(a, false)));
    if (a.tool === "gmail_create_draft") {
      actions.append(el("span", "small muted", "Marvin creates the draft; you send it from Gmail."));
    }
    li.append(actions);
  }
  return li;
}

function render() {
  if (!root) return;
  root.replaceChildren();
  const head = el("div", "panel-head");
  const waiting = pending().length;
  head.append(el("span", "label muted", "Approvals"), el("span", `pill${waiting ? " attention-pill" : ""}`,
    waiting ? `${waiting} waiting` : "None waiting"));
  root.append(head);
  if (failed) {
    root.append(el("p", "small muted", failed));
    root.append(button("Try again", "small-button gap-top", () => load()));
    return;
  }
  if (!list.length) {
    root.append(el("p", "small muted", "When a background task wants to write outside this computer (an email draft), "
      + "it waits here until you approve it. Marvin never sends anything itself."));
    return;
  }
  const ul = el("ul", "approvals-list stack");
  list.forEach((a) => ul.append(item(a)));
  root.append(ul);
}

export async function load() {
  try {
    const r = await api("/api/approvals");
    list = r.approvals || [];
    failed = "";
  } catch (e) {
    failed = `Could not read the approvals: ${e.message}`;
  }
  render();
  emit("approvals", pending());
}

/** Renders into `target` (an element, or its id). */
export function setup(target) {
  root = typeof target === "string" ? document.getElementById(target) : target || null;
  on("approval", () => load());
  on("reconnected", () => load());
  render();
}


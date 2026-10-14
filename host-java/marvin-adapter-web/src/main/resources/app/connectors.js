// SPDX-License-Identifier: MIT
// Connectors (docs/design.md 7; docs/connectors.md): Google Calendar (read only) and Gmail (search, and drafts after
// your approval), with your own Google OAuth client. Connecting opens Google's consent page in a new tab of this
// computer's browser; Google sends it back to Marvin's host. Tokens stay in the Keychain (or a file only you can read);
// Disconnect revokes them. Marvin never asks for a permission to send or delete.
//
// setup(root) renders into `root` (W5 places it, under Marvin); load() reads /api/connectors.

import { el, api, post, on, toast } from "./core.js";

const STATE = { connected: "Connected", disconnected: "Not connected", error: "Needs attention" };
const CLASS = { READ: "reads", LOCAL_WRITE: "writes on this computer", EXTERNAL_WRITE: "writes in your account, after your approval" };
const SCOPE = (s) => s.split("/auth/").pop();

let root = null;
let data = null;
let failed = "";
let polling = 0;

function button(text, cls, fn) {
  const b = el("button", cls, text);
  b.type = "button";
  b.addEventListener("click", fn);
  return b;
}

async function connect(c) {
  // the tab opens now, while the click still counts as the owner's, then goes to Google's page
  const tab = window.open("", "_blank");
  try {
    const r = await post(`/api/connectors/${c.id}/connect`);
    if (tab) {
      tab.opener = null;
      tab.location.href = r.auth_url;
    } else {
      window.location.href = r.auth_url;
    }
    watch(c.id);
  } catch (e) {
    if (tab) tab.close();
    toast(e.message);
  }
}

/** Reads the connectors every 3 s for 3 minutes, or until this one is connected. */
function watch(id) {
  clearInterval(polling);
  const until = Date.now() + 180000;
  polling = setInterval(async () => {
    await load();
    const c = data && data.connectors.find((x) => x.id === id);
    if (Date.now() > until || (c && c.state === "connected")) clearInterval(polling);
  }, 3000);
}

async function disconnect(c) {
  if (!window.confirm(`Disconnect ${c.name}? Marvin's access is revoked at Google.`)) return;
  try {
    data = await post(`/api/connectors/${c.id}/disconnect`);
    toast(`${c.name} disconnected`);
  } catch (e) {
    toast(e.message);
  }
  render();
}

function item(c) {
  const li = el("li", `panel connector ${c.state}`);
  const head = el("div", "panel-head");
  head.append(el("span", "label", c.name), el("span", `pill${c.state === "error" ? " attention-pill" : ""}`,
    STATE[c.state] || c.state));
  li.append(head);
  if (c.account) li.append(el("p", "small", `Connected as ${c.account}`));
  if (c.last_error) li.append(el("p", "small form-error", c.last_error));
  const tools = el("ul", "small muted");
  for (const t of c.tools || []) tools.append(el("li", null, `${t.name}: ${CLASS[t.class] || t.class}`));
  li.append(tools);
  li.append(el("p", "small muted mono", `Permissions: ${(c.scopes || []).map(SCOPE).join(", ")}`));
  const actions = el("div", "row-actions");
  if (c.state === "disconnected" || c.state === "error") {
    const b = button(c.state === "error" ? "Connect again" : "Connect", "primary small-button", () => connect(c));
    b.disabled = !data.oauth_client;
    actions.append(b);
  }
  if (c.state !== "disconnected") actions.append(button("Disconnect", "small-button", () => disconnect(c)));
  li.append(actions);
  return li;
}

function render() {
  if (!root) return;
  root.replaceChildren();
  if (failed) {
    root.append(el("p", "small muted", failed), button("Try again", "small-button gap-top", () => load()));
    return;
  }
  if (!data) {
    root.append(el("p", "small muted", "Reading the connectors…"));
    return;
  }
  if (!data.oauth_client) {
    const n = el("div", "panel");
    n.append(el("p", "notice-title", "First, your own Google OAuth client"));
    n.append(el("p", "small", "Marvin connects with a Google OAuth client you create (about 10 minutes, once): a "
      + "“Desktop app” client in Google Cloud’s console, its JSON file saved as google-oauth-client.json in Marvin’s "
      + "data folder. The steps are in docs/connectors.md."));
    root.append(n);
  }
  const ul = el("ul", "connectors-list stack");
  data.connectors.forEach((c) => ul.append(item(c)));
  root.append(ul);
  root.append(el("p", "small muted", "Connect from this computer’s browser: Google sends you back to Marvin’s host on "
    + "this computer. Marvin never sends or deletes an email: it can only create drafts you approve, and you send them."));
}

export async function load() {
  try {
    data = await api("/api/connectors");
    failed = "";
  } catch (e) {
    failed = `Could not read the connectors: ${e.message}`;
  }
  render();
}

/** Renders into `target` (an element, or its id). */
export function setup(target) {
  root = typeof target === "string" ? document.getElementById(target) : target || null;
  on("reconnected", () => load());
  window.addEventListener("focus", () => { if (root && root.isConnected) load(); });
  render();
}

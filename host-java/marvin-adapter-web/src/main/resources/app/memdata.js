// SPDX-License-Identifier: MIT
// Facts as the app shows them: where each one comes from, and the dialog with its sources, quoted and dated
// (GET /api/memory/facts/{id}). Shared by Home, Talk and Memory.

import { api, el, emit, whenLabel, openDialog } from "./core.js";

const SOURCE_WORDS = {
  conversation: "From your conversation", owner: "You told Marvin", brain: "From what Marvin noticed",
  presence: "From what Marvin noticed", app: "Written in the app",
};

/** "From your conversation · Today, 11:16" from a fact and, when known, its first source event. */
export function sourceLine(fact, firstSource) {
  let what;
  if (firstSource) what = SOURCE_WORDS[firstSource.source] || `From ${firstSource.source}`;
  else what = fact.origin === "owner" ? "You told Marvin" : fact.origin === "task" ? "From a task" : "From your conversations";
  return `${what} · ${whenLabel(firstSource ? firstSource.ts : fact.learned_at)}`;
}

export function statusPill(fact) {
  if (fact.pinned) return el("span", "pill", "Pinned");
  if (fact.origin === "owner") return el("span", "pill", "Yours");
  if (!fact.reviewed) return el("span", "pill attention-pill", "Suggested");
  return el("span", "pill", "Reviewed");
}

const SENSITIVITY = { normal: "", personal: "Personal", sensitive: "Sensitive" };

/** Opens a fact with its sources and versions. */
export async function openFact(id) {
  const body = openDialog("A memory", { label: "Memory · with its source" });
  body.append(el("p", "muted", "Loading…"));
  let d;
  try {
    d = await api(`/api/memory/facts/${encodeURIComponent(id)}`);
  } catch (e) {
    body.replaceChildren(el("p", null, `This memory cannot be read: ${e.message}`));
    return;
  }
  const f = d.fact;
  document.getElementById("dialog-title").textContent = f.statement;
  body.replaceChildren();
  const tags = el("div", "fact-tags");
  tags.append(statusPill(f));
  if (SENSITIVITY[f.sensitivity]) tags.append(el("span", "pill", SENSITIVITY[f.sensitivity]));
  if (f.status !== "current") tags.append(el("span", "pill", { past: "No longer true", replaced: "Replaced", archived: "Archived" }[f.status] || f.status));
  body.append(tags);
  body.append(el("p", null, `${f.when[0].toUpperCase()}${f.when.slice(1)}. ${f.use_count ? `Used in ${f.use_count} answer${f.use_count === 1 ? "" : "s"}` : "Not used in an answer yet"}${f.last_used_at ? `, last ${whenLabel(f.last_used_at)}` : ""}.`));
  if (d.sources.length) {
    body.append(el("h3", "gap-top", d.sources.length === 1 ? "Where it comes from" : `Where it comes from (${d.sources.length})`));
    for (const s of d.sources) {
      const q = el("blockquote", "source-quote");
      q.append(el("p", null, s.text));
      const where = SOURCE_WORDS[s.source] || s.source;
      const line = el("span", "source", `${where} · ${whenLabel(s.ts)}`);
      q.append(line);
      if (s.conversation) {
        const a = el("a", "small quiet-link", "Open that conversation");
        a.href = `#activity/history`;
        a.addEventListener("click", () => {
          emit("goto-conversation", { day: s.conversation.day, entry: s.conversation.entry });
          document.getElementById("dialog").close();
        });
        q.append(document.createElement("br"), a);
      }
      body.append(q);
    }
  } else {
    body.append(el("p", null, "Its source is no longer kept."));
  }
  if (d.versions.length > 1) {
    body.append(el("h3", "gap-top", "Earlier versions"));
    const ul = el("ul", "worker-lines gap-top");
    for (const v of d.versions.filter((v) => v.id !== f.id)) {
      const li = el("li");
      li.append(el("span", null, v.statement), el("span", null, whenLabel(v.learned_at)));
      ul.append(li);
    }
    body.append(ul);
  }
  body.append(el("p", "small", `Learned ${whenLabel(f.learned_at)}${f.extracted_by ? ` by ${f.extracted_by}` : ""}. Confidence ${Math.round(f.confidence * 100)} %, importance ${f.importance} of 10.`));
}

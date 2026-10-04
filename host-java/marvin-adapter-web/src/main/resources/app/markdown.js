// SPDX-License-Identifier: MIT
// A small Markdown reader for what Marvin writes (a task's result): headings, paragraphs, lists (nested by
// indentation), tables, code blocks, quotes, rules; bold, italics, inline code and links. Built with DOM nodes only,
// never innerHTML: the text is the model's, so nothing in it can become markup. Links open outside the app and only
// for http(s) addresses.

import { el } from "./core.js";

// underscores emphasise only between words, so tool names such as get_weather stay as they are
const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*|(?<!\w)__[^_]+__(?!\w))|(\*[^*\s][^*]*\*|(?<!\w)_[^_\s][^_]*_(?!\w))|(\[[^\]]+\]\([^)\s]+\))/;

/** The inline parts of one line of text, as nodes. */
export function inline(text) {
  const out = [];
  let rest = text;
  while (rest) {
    const m = INLINE.exec(rest);
    if (!m) { out.push(document.createTextNode(rest)); break; }
    if (m.index) out.push(document.createTextNode(rest.slice(0, m.index)));
    const tok = m[0];
    if (m[1]) out.push(el("code", null, tok.slice(1, -1)));
    else if (m[2]) { const b = el("strong"); b.append(...inline(tok.slice(2, -2))); out.push(b); }
    else if (m[3]) { const i = el("em"); i.append(...inline(tok.slice(1, -1))); out.push(i); }
    else {
      const [, label, href] = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(tok);
      if (/^https?:\/\//i.test(href)) {
        const a = el("a", null, label);
        a.href = href;
        a.target = "_blank";
        a.rel = "noopener noreferrer";
        out.push(a);
      } else out.push(document.createTextNode(label));
    }
    rest = rest.slice(m.index + tok.length);
  }
  return out;
}

const cells = (line) => line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());
const isRule = (line) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(line);
const listItem = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;

/** The Markdown `text` as a fragment of block nodes. */
export function renderMarkdown(text) {
  const frag = document.createDocumentFragment();
  const lines = String(text || "").replace(/\r\n?/g, "\n").split("\n");
  let i = 0;
  let para = [];
  const flush = () => {
    if (!para.length) return;
    const p = el("p");
    para.forEach((l, k) => { if (k) p.append(el("br")); p.append(...inline(l)); });
    frag.append(p);
    para = [];
  };
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { flush(); i++; continue; }
    const fence = /^\s*```/.exec(line);
    if (fence) {
      flush();
      const code = [];
      i++;
      while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++]);
      i++;
      const pre = el("pre");
      pre.append(el("code", null, code.join("\n")));
      frag.append(pre);
      continue;
    }
    const h = /^\s*(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
    if (h) {
      flush();
      const level = Math.min(6, h[1].length + 3);       // inside a panel: h4 and below
      const e = el(`h${level}`);
      e.append(...inline(h[2]));
      frag.append(e);
      i++;
      continue;
    }
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { flush(); frag.append(el("hr")); i++; continue; }
    if (line.includes("|") && i + 1 < lines.length && isRule(lines[i + 1])) {
      flush();
      const table = el("table");
      const head = el("thead"), tr = el("tr");
      for (const c of cells(line)) { const th = el("th"); th.append(...inline(c)); tr.append(th); }
      head.append(tr);
      const body = el("tbody");
      i += 2;
      while (i < lines.length && lines[i].includes("|") && lines[i].trim()) {
        const row = el("tr");
        for (const c of cells(lines[i])) { const td = el("td"); td.append(...inline(c)); row.append(td); }
        body.append(row);
        i++;
      }
      table.append(head, body);
      const wrap = el("div", "md-table");
      wrap.append(table);
      frag.append(wrap);
      continue;
    }
    if (/^\s*>/.test(line)) {
      flush();
      const quote = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) quote.push(lines[i++].replace(/^\s*>\s?/, ""));
      const q = el("blockquote");
      q.append(renderMarkdown(quote.join("\n")));
      frag.append(q);
      continue;
    }
    if (listItem.test(line)) {
      flush();
      const stack = [];
      while (i < lines.length && (listItem.test(lines[i]) || (/^\s{2,}\S/.test(lines[i]) && stack.length))) {
        const m = listItem.exec(lines[i]);
        if (!m) {                                   // a continuation line of the item above
          const li = stack[stack.length - 1].list.lastElementChild;
          if (li) li.append(" ", ...inline(lines[i].trim()));
          i++;
          continue;
        }
        const indent = m[1].replace(/\t/g, "  ").length;
        const ordered = /\d/.test(m[2]);
        while (stack.length && indent < stack[stack.length - 1].indent) stack.pop();
        let top = stack[stack.length - 1];
        if (!top || indent > top.indent) {
          const list = el(ordered ? "ol" : "ul");
          if (top && top.list.lastElementChild) top.list.lastElementChild.append(list); else frag.append(list);
          top = { indent, list };
          stack.push(top);
        }
        const li = el("li");
        li.append(...inline(m[3]));
        top.list.append(li);
        i++;
      }
      continue;
    }
    para.push(line.trim());
    i++;
  }
  flush();
  return frag;
}

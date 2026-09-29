// SPDX-License-Identifier: MIT
// One day's timeline and numbers (Home: today; Activity > History: any day). When you were around, when you
// were seated, and break reminders, on an axis that grows with the day.

import { app, el, SVG, fmtDuration, fmtTime, fmtHour, longDate, dayLabel, durationNodes } from "./core.js";

export class DayCard {
  constructor(root) {
    this.root = root;
    this.day = null;
  }

  f(name) { return this.root.querySelector(`[data-f="${name}"]`); }

  set(name, value) {
    const e = this.f(name);
    if (!e) return;
    if (Array.isArray(value)) e.replaceChildren(...value); else e.textContent = value;
  }

  render(d) {
    this.day = d;
    const isToday = app.today && d.date === app.today.date;
    const title = this.f("title");
    if (title) title.textContent = isToday ? "Today" : "Day";
    this.set("label", isToday && title ? longDate(d.date) : dayLabel(d.date));
    this.set("seated", durationNodes(d.seated_s));
    this.set("sessions", String(d.sessions));
    this.set("breaks", String(d.breaks));
    this.set("longest", durationNodes(d.longest_s));
    this.set("first", d.first_arrival ? fmtTime(d.first_arrival) : "–");
    const facts = [];
    if (d.first_arrival) facts.push(`First seen ${fmtTime(d.first_arrival)}`);
    if (d.last_departure) facts.push(`last left ${fmtTime(d.last_departure)}`);
    if (d.breath_rate != null) facts.push(`breathing ${Math.round(d.breath_rate)}/min`);
    if (d.heart_rate != null) facts.push(`heart ${Math.round(d.heart_rate)}/min on average`);
    if (!d.first_arrival) facts.push(isToday ? "Nobody at the desk yet today." : "Nobody at the desk that day.");
    this.set("facts", facts.join(" · "));
    this.timeline(d);
  }

  timeline(d) {
    const svg = this.f("timeline");
    if (!svg || !svg.getClientRects().length) return;          // not on screen: drawn when shown
    const dayStart = d.start, dayEnd = d.end;
    const tl = d.timeline;
    const first = tl.present.length ? tl.present[0][0] : null;
    const last = d.now || (tl.present.length ? tl.present[tl.present.length - 1][1] : null);
    const hourOf = (ts) => (ts - dayStart) / 3600;
    const h0 = Math.min(7, first != null ? Math.floor(hourOf(first)) : 7);
    let h1 = Math.max(19, last != null ? Math.ceil(hourOf(last) + 0.01) : 19);
    h1 = Math.min(h1, Math.round((dayEnd - dayStart) / 3600));
    const lo = dayStart + h0 * 3600, hi = dayStart + h1 * 3600;
    const W = 1000, H = 44, y = 12, h = 24;
    const x = (ts) => ((Math.min(Math.max(ts, lo), hi) - lo) / (hi - lo)) * W;
    const width = Math.max(1, svg.getBoundingClientRect().width || 600);
    svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
    svg.replaceChildren();
    const rect = (cls, a, b, yy = y, hh = h, r = 4) => {
      const e = document.createElementNS(SVG, "rect");
      e.setAttribute("class", cls);
      e.setAttribute("x", x(a)); e.setAttribute("y", yy);
      e.setAttribute("width", Math.max(1.5, x(b) - x(a))); e.setAttribute("height", hh);
      e.setAttribute("rx", r * (W / width));
      e.setAttribute("ry", r);
      svg.appendChild(e);
    };
    const line = (cls, xx, y1, y2) => {
      const g = document.createElementNS(SVG, "line");
      g.setAttribute("class", cls);
      g.setAttribute("x1", xx); g.setAttribute("x2", xx);
      g.setAttribute("y1", y1); g.setAttribute("y2", y2);
      g.setAttribute("vector-effect", "non-scaling-stroke");
      svg.appendChild(g);
    };
    rect("tl-track", lo, hi, y, h, 7);
    const pxPerHour = width / (h1 - h0);
    const step = [1, 2, 3, 4, 6].find((k) => k * pxPerHour >= 58) || 6;
    const margin = 64 / pxPerHour;
    for (let k = Math.ceil(h0 / step) * step; k <= h1; k += step) {
      if (k !== h0 && k !== h1) line("tl-grid", x(dayStart + k * 3600), y + h + 2, y + h + 6);
    }
    for (const [a, b] of tl.present) rect("tl-present", a, b);
    for (const [a, b] of tl.seated) rect("tl-seated", a, b, y + 3, h - 6, 3);
    for (const t of tl.reminders) {
      const e = document.createElementNS(SVG, "ellipse");
      e.setAttribute("class", "tl-reminder");
      e.setAttribute("cx", x(t)); e.setAttribute("cy", 5);
      e.setAttribute("rx", 3.5 * (W / width)); e.setAttribute("ry", 3.5);
      svg.appendChild(e);
    }
    if (d.now) line("tl-now", x(d.now), y - 4, y + h + 4);
    const axis = this.f("axis");
    axis.replaceChildren();
    const labels = [h0];
    for (let k = Math.ceil(h0 / step) * step; k < h1; k += step) {
      if (k - h0 >= margin && h1 - k >= margin) labels.push(k);
    }
    labels.push(h1);
    for (const k of labels) {
      const s = el("span", null, fmtHour(k));
      s.style.left = `${((k - h0) / (h1 - h0)) * 100}%`;
      axis.appendChild(s);
    }
    const parts = [`Seated ${fmtDuration(d.seated_s)} in ${d.sessions} session${d.sessions === 1 ? "" : "s"}`];
    if (tl.reminders.length) parts.push(`${tl.reminders.length} break reminder${tl.reminders.length === 1 ? "" : "s"}`);
    this.set("desc", `Timeline from ${fmtHour(h0)} to ${fmtHour(h1)}. ${parts.join(", ")}.`);
  }

  redraw() { if (this.day) this.timeline(this.day); }
}

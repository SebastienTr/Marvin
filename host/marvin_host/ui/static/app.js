// SPDX-License-Identifier: MIT
// Marvin's app: live face and status (Server-Sent Events), the day, the week, recent events,
// settings. Vanilla JS, no build step, no network access beyond this server.
"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const SVG = "http://www.w3.org/2000/svg";
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const QUIET_KINDS = new Set(["vitals_acquired", "vitals_lost"]);
  const SYSTEM_KINDS = new Set(["host_started", "host_stopped", "robot_online", "robot_offline"]);

  const app = {
    settings: { clock: "24h", break_interval_min: 50 },
    today: null,          // today's stats, from the server
    shown: null,          // the day on screen (YYYY-MM-DD); null = today
    history: [],
    lastEventId: 0,
    state: null,
  };

  // ------------------------------------------------------------------ formatting

  function fmtDuration(s, { short = false } = {}) {
    s = Math.max(0, s || 0);
    const m = Math.floor(s / 60);
    if (m < 60) return short ? `${m}m` : `${m} min`;
    const h = Math.floor(m / 60), r = m % 60;
    if (short) return r ? `${h}h${String(r).padStart(2, "0")}` : `${h}h`;
    return r ? `${h} h ${String(r).padStart(2, "0")}` : `${h} h`;
  }

  // "4 h 12" with the units smaller
  function durationHTML(s) {
    const m = Math.floor(Math.max(0, s || 0) / 60);
    if (m < 60) return `${m}<small>min</small>`;
    const h = Math.floor(m / 60), r = m % 60;
    return r ? `${h}<small>h</small> ${String(r).padStart(2, "0")}` : `${h}<small>h</small>`;
  }

  function fmtTime(ts) {
    const d = new Date(ts * 1000);
    const h = d.getHours(), m = String(d.getMinutes()).padStart(2, "0");
    if (app.settings.clock === "12h") return `${((h + 11) % 12) + 1}:${m} ${h < 12 ? "am" : "pm"}`;
    return `${String(h).padStart(2, "0")}:${m}`;
  }

  function fmtHour(h) {
    if (app.settings.clock === "12h") return `${((h + 11) % 12) + 1}${h % 24 < 12 ? "am" : "pm"}`;
    return `${String(h % 24).padStart(2, "0")}:00`;
  }

  const isoDate = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  const parseIso = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };

  function dayLabel(iso) {
    const d = parseIso(iso), today = parseIso(app.today ? app.today.date : isoDate(new Date()));
    const diff = Math.round((today - d) / 86400000);
    if (diff === 0) return "Today";
    if (diff === 1) return "Yesterday";
    return d.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" });
  }

  async function api(path, options) {
    const r = await fetch(path, { credentials: "same-origin", cache: "no-store", ...options });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(body.error || `HTTP ${r.status}`);
    return body;
  }

  // ------------------------------------------------------------------ live state

  function renderState(s) {
    app.state = s;
    $("status").textContent = s.status;
    document.body.dataset.mood = s.mood;
    const vit = s.breath_rate != null && s.heart_rate != null;
    $("vitals").hidden = !vit;
    if (vit) {
      $("breath").textContent = `${Math.round(s.breath_rate)}/min`;
      $("heart").textContent = `${Math.round(s.heart_rate)}/min`;
      $("vitals").setAttribute("aria-label", `Breathing ${Math.round(s.breath_rate)} per minute, heart ${Math.round(s.heart_rate)} per minute`);
    }
    $("face").alt = s.expression ? `Marvin's face, ${s.expression}` : "Marvin's face";

    const b = s.break, card = $("break");
    const pct = Math.round(100 * b.progress);
    $("break-fill").style.width = `${s.seated ? pct : 0}%`;
    $("break-bar").setAttribute("aria-valuenow", String(s.seated ? pct : 0));
    $("break-now").textContent = s.seated ? fmtDuration(b.seated_s) : "–";
    $("break-of").textContent = `of ${fmtDuration(b.interval_s)}`;
    card.classList.toggle("due", b.due);
    card.classList.toggle("idle", !s.seated);
    let hint;
    if (!s.online) hint = "Marvin is not connected.";
    else if (b.due && s.quiet) hint = "Quiet hours: Marvin keeps it to himself.";
    else if (b.due) hint = "Time to stand up for a few minutes.";
    else if (s.seated) hint = `A break in ${fmtDuration(b.interval_s - b.seated_s)}. Standing up resets the timer.`;
    else if (s.present) hint = "You're up. The timer starts when you sit down.";
    else hint = "The timer starts when you sit down.";
    $("break-hint").textContent = hint;
  }

  // ------------------------------------------------------------------ day

  function renderDay(d) {
    const isToday = app.today && d.date === app.today.date;
    $("day-h").textContent = isToday ? "Today" : "Day";
    $("day-label").textContent = isToday
      ? parseIso(d.date).toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" })
      : dayLabel(d.date);
    $("day-next").disabled = isToday;

    $("s-seated").innerHTML = durationHTML(d.seated_s);
    $("s-sessions").textContent = d.sessions;
    $("s-breaks").textContent = d.breaks;
    $("s-longest").innerHTML = durationHTML(d.longest_s);

    const facts = [];
    if (d.first_arrival) facts.push(`First seen ${fmtTime(d.first_arrival)}`);
    if (d.last_departure) facts.push(`last left ${fmtTime(d.last_departure)}`);
    if (d.breath_rate != null) facts.push(`breathing ${Math.round(d.breath_rate)}/min`);
    if (d.heart_rate != null) facts.push(`heart ${Math.round(d.heart_rate)}/min on average`);
    if (!d.first_arrival) facts.push(isToday ? "Nobody at the desk yet today." : "Nobody at the desk that day.");
    $("facts").textContent = facts.join(" · ");

    renderTimeline(d);
  }

  function renderTimeline(d) {
    const svg = $("timeline");
    const dayStart = d.start, dayEnd = d.end;
    const tl = d.timeline;
    const edge = (xs) => xs.length ? xs : null;
    const first = edge(tl.present) ? tl.present[0][0] : null;
    const last = d.now || (edge(tl.present) ? tl.present[tl.present.length - 1][1] : null);
    const hourOf = (ts) => (ts - dayStart) / 3600;
    let h0 = Math.min(7, first != null ? Math.floor(hourOf(first)) : 7);
    let h1 = Math.max(19, last != null ? Math.ceil(hourOf(last) + 0.01) : 19);
    h1 = Math.min(h1, Math.round((dayEnd - dayStart) / 3600));
    const lo = dayStart + h0 * 3600, hi = dayStart + h1 * 3600;
    const W = 1000, H = 44, y = 14, h = 22;
    const x = (ts) => ((Math.min(Math.max(ts, lo), hi) - lo) / (hi - lo)) * W;

    svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
    svg.replaceChildren();
    const rect = (cls, a, b, yy = y, hh = h, r = 3) => {
      const e = document.createElementNS(SVG, "rect");
      e.setAttribute("class", cls);
      e.setAttribute("x", x(a)); e.setAttribute("y", yy);
      e.setAttribute("width", Math.max(1.5, x(b) - x(a))); e.setAttribute("height", hh);
      e.setAttribute("rx", r);
      svg.appendChild(e);
    };
    rect("tl-track", lo, hi, y, h, 5);
    const pxPerHour = Math.max(1, svg.getBoundingClientRect().width || 600) / (h1 - h0);
    const step = [1, 2, 3, 4, 6].find((k) => k * pxPerHour >= 58) || 6;
    const margin = 64 / pxPerHour;       // hours kept clear next to the first and last label
    for (let k = Math.ceil(h0 / step) * step; k <= h1; k += step) {
      if (k === h0 || k === h1) continue;
      const g = document.createElementNS(SVG, "line");
      g.setAttribute("class", "tl-grid");
      g.setAttribute("x1", x(dayStart + k * 3600)); g.setAttribute("x2", x(dayStart + k * 3600));
      g.setAttribute("y1", y + h + 2); g.setAttribute("y2", y + h + 6);
      g.setAttribute("vector-effect", "non-scaling-stroke");
      svg.appendChild(g);
    }
    for (const [a, b] of tl.present) rect("tl-present", a, b);
    for (const [a, b] of tl.seated) rect("tl-seated", a, b, y + 3, h - 6, 2);
    for (const t of tl.reminders) {
      // an ellipse that stays round although the SVG stretches horizontally
      const e = document.createElementNS(SVG, "ellipse");
      const k = W / Math.max(1, svg.getBoundingClientRect().width || W);
      e.setAttribute("class", "tl-reminder");
      e.setAttribute("cx", x(t)); e.setAttribute("cy", 5);
      e.setAttribute("rx", 3.5 * k); e.setAttribute("ry", 3.5);
      svg.appendChild(e);
    }
    if (d.now) {
      const n = document.createElementNS(SVG, "line");
      n.setAttribute("class", "tl-now");
      n.setAttribute("x1", x(d.now)); n.setAttribute("x2", x(d.now));
      n.setAttribute("y1", y - 4); n.setAttribute("y2", y + h + 4);
      n.setAttribute("vector-effect", "non-scaling-stroke");
      svg.appendChild(n);
    }

    const axis = $("axis");
    axis.replaceChildren();
    const labels = [h0];
    for (let k = Math.ceil(h0 / step) * step; k < h1; k += step) {
      if (k - h0 >= margin && h1 - k >= margin) labels.push(k);
    }
    labels.push(h1);
    for (const k of labels) {
      const s = document.createElement("span");
      s.textContent = fmtHour(k);
      s.style.left = `${((k - h0) / (h1 - h0)) * 100}%`;
      axis.appendChild(s);
    }
    const parts = [`Seated ${fmtDuration(d.seated_s)} in ${d.sessions} session${d.sessions === 1 ? "" : "s"}`];
    if (tl.reminders.length) parts.push(`${tl.reminders.length} break reminder${tl.reminders.length === 1 ? "" : "s"}`);
    $("timeline-desc").textContent = `Timeline from ${fmtHour(h0)} to ${fmtHour(h1)}. ${parts.join(", ")}.`;
  }

  async function showDay(iso) {
    app.shown = !app.today || iso === app.today.date ? null : iso;
    if (!app.shown) { if (app.today) renderDay(app.today); }
    else renderDay(await api(`/api/day?date=${iso}`));
    renderWeek();
  }

  function shiftDay(delta) {
    const cur = parseIso(app.shown || app.today.date);
    cur.setDate(cur.getDate() + delta);
    const iso = isoDate(cur);
    if (app.today && iso > app.today.date) return;
    showDay(iso).catch(() => {});
  }

  // ------------------------------------------------------------------ week

  async function loadWeek() {
    const r = await api("/api/history?days=7");
    app.history = r.days;
    renderWeek();
  }

  function renderWeek() {
    const days = app.history.slice();
    if (!days.length) return;
    if (app.today) {       // keep today's bar live
      const i = days.findIndex((d) => d.date === app.today.date);
      if (i >= 0) days[i] = { ...days[i], seated_s: app.today.seated_s };
    }
    const max = Math.max(3600 * 4, ...days.map((d) => d.seated_s));
    const ol = $("week");
    ol.replaceChildren();
    const shown = app.shown || (app.today && app.today.date);
    let sum = 0, n = 0;
    for (const d of days) {
      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.type = "button";
      const isToday = app.today && d.date === app.today.date;
      if (isToday) btn.classList.add("today");
      btn.setAttribute("aria-pressed", String(d.date === shown));
      const date = parseIso(d.date);
      const name = date.toLocaleDateString("en-GB", { weekday: "short" });
      btn.setAttribute("aria-label", `${date.toLocaleDateString("en-GB", { weekday: "long" })}: seated ${fmtDuration(d.seated_s)}`);
      btn.innerHTML = `<span class="val">${d.seated_s >= 60 ? fmtDuration(d.seated_s, { short: true }) : ""}</span>` +
        `<span class="col"><span class="fill"></span></span><span class="day">${name}</span>`;
      btn.querySelector(".fill").style.height = `${Math.max(2, (d.seated_s / max) * 100)}%`;
      btn.addEventListener("click", () => showDay(d.date).catch(() => {}));
      li.appendChild(btn);
      ol.appendChild(li);
      if (!isToday && d.seated_s > 0) { sum += d.seated_s; n += 1; }
    }
    $("week-avg").textContent = n ? `${fmtDuration(sum / n)} a day on average` : "";
  }

  // ------------------------------------------------------------------ events

  function eventItem(e) {
    const li = document.createElement("li");
    li.dataset.id = e.id;
    const t = document.createElement("time");
    t.dateTime = new Date(e.ts * 1000).toISOString();
    t.textContent = fmtTime(e.ts);
    const p = document.createElement("span");
    p.textContent = e.text;
    if (SYSTEM_KINDS.has(e.kind)) p.className = "system";
    if (e.kind === "still_long") p.className = "attention";
    li.append(t, p);
    return li;
  }

  async function loadEvents() {
    const r = await api("/api/events?quiet=1&limit=8");
    const ol = $("events");
    ol.replaceChildren(...r.events.map(eventItem));
    app.lastEventId = r.events.reduce((m, e) => Math.max(m, e.id), app.lastEventId);
    $("events-empty").hidden = r.events.length > 0;
  }

  function addEvent(e) {
    if (e.id <= app.lastEventId) return;
    app.lastEventId = e.id;
    if (QUIET_KINDS.has(e.kind)) return;
    const ol = $("events");
    const li = eventItem(e);
    li.classList.add("fresh");
    ol.prepend(li);
    while (ol.children.length > 8) ol.lastElementChild.remove();
    $("events-empty").hidden = true;
  }

  // ------------------------------------------------------------------ face

  function startFace() {
    const img = $("face");
    let timer = null, busy = false;
    const period = () => (reducedMotion.matches ? 1000 : 160);
    const tick = () => {
      timer = null;
      if (document.hidden || busy) return;
      busy = true;
      const next = new Image();
      next.onload = () => { img.src = next.src; busy = false; schedule(period()); };
      next.onerror = () => { busy = false; schedule(3000); };
      next.src = `/face.png?t=${Date.now()}`;
    };
    const schedule = (ms) => { if (!timer) timer = setTimeout(tick, ms); };
    document.addEventListener("visibilitychange", () => { if (!document.hidden) schedule(0); });
    schedule(0);
  }

  // ------------------------------------------------------------------ stream

  function connect() {
    const conn = $("conn"), text = $("conn-text");
    const es = new EventSource("/api/stream");
    const status = (cls, label) => { conn.className = `conn ${cls}`; text.textContent = label; };
    es.addEventListener("open", () => status("live", "Live"));
    es.addEventListener("error", () => status("lost", "Reconnecting"));
    es.addEventListener("hello", (m) => applySettings(JSON.parse(m.data).settings));
    es.addEventListener("state", (m) => {
      const s = JSON.parse(m.data);
      renderState(s);
      if (!s.online) status("lost", "Robot offline"); else status("live", "Live");
    });
    es.addEventListener("today", (m) => {
      const d = JSON.parse(m.data);
      const newDay = app.today && app.today.date !== d.date;
      app.today = d;
      if (!app.shown) renderDay(d);
      if (newDay) loadWeek().catch(() => {}); else renderWeek();
    });
    es.addEventListener("event", (m) => addEvent(JSON.parse(m.data)));
    es.addEventListener("settings", (m) => applySettings(JSON.parse(m.data)));
  }

  // ------------------------------------------------------------------ settings

  function applySettings(s) {
    const clockChanged = s.clock !== app.settings.clock;
    app.settings = s;
    if (clockChanged) {
      if (app.today && !app.shown) renderDay(app.today);
      loadEvents().catch(() => {});
    }
  }

  function setupSettings() {
    const dlg = $("settings"), form = $("settings-form"), err = $("settings-error");
    const quiet = $("f-quiet");
    const syncQuiet = () => { $("f-quiet-start").disabled = $("f-quiet-end").disabled = !quiet.checked; };
    quiet.addEventListener("change", syncQuiet);

    $("open-settings").addEventListener("click", async () => {
      err.hidden = true;
      try {
        const r = await api("/api/settings");
        const s = r.settings;
        $("f-break").value = s.break_interval_min;
        quiet.checked = s.quiet_hours.enabled;
        $("f-quiet-start").value = s.quiet_hours.start;
        $("f-quiet-end").value = s.quiet_hours.end;
        $("f-voice").checked = s.voice;
        form.querySelector(`input[name="clock"][value="${s.clock}"]`).checked = true;
        const about = $("about-data");
        about.replaceChildren();
        if (r.about.data_dir) {
          const code = document.createElement("code");
          code.textContent = r.about.data_dir;
          about.append("History and settings are stored on this computer only, in ", code,
            ". Delete that folder to erase them.");
        } else {
          about.textContent = "This is a demo: nothing is kept.";
        }
        syncQuiet();
      } catch (e) { /* show the form anyway */ }
      dlg.showModal();
    });
    const close = () => dlg.close();
    $("close-settings").addEventListener("click", close);
    $("cancel-settings").addEventListener("click", close);
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      err.hidden = true;
      const body = {
        break_interval_min: Number($("f-break").value),
        quiet_hours: { enabled: quiet.checked, start: $("f-quiet-start").value || "22:00", end: $("f-quiet-end").value || "07:00" },
        voice: $("f-voice").checked,
        clock: form.querySelector('input[name="clock"]:checked').value,
      };
      try {
        const r = await api("/api/settings", {
          method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
        });
        applySettings(r.settings);
        dlg.close();
      } catch (e) {
        err.textContent = e.message;
        err.hidden = false;
      }
    });
  }

  // ------------------------------------------------------------------ start

  async function init() {
    setupSettings();
    $("day-prev").addEventListener("click", () => shiftDay(-1));
    $("day-next").addEventListener("click", () => shiftDay(1));
    startFace();
    try {
      const r = await api("/api/state");
      app.settings = r.settings;
      app.today = r.today;
      renderState(r.state);
      renderDay(r.today);
    } catch (e) {
      $("status").textContent = "Cannot reach Marvin";
    }
    await Promise.all([loadWeek(), loadEvents()]).catch(() => {});
    connect();
    let resizeTimer;
    window.addEventListener("resize", () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        if (app.shown) showDay(app.shown).catch(() => {}); else if (app.today) renderTimeline(app.today);
      }, 150);
    });
  }

  init();
})();

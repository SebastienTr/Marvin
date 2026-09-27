// SPDX-License-Identifier: MIT
// Marvin's app: live face and status, the conversation and the voice's controls, the robot's
// devices and sensors, the day, the week, the log, settings. Server-Sent Events for everything
// live. Vanilla JS, no build step, no network access beyond this server.
"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const SVG = "http://www.w3.org/2000/svg";
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const wide = window.matchMedia("(min-width: 1200px)");
  const VIEWS = ["home", "talk", "robot", "history", "settings"];
  const QUIET_KINDS = new Set(["vitals_acquired", "vitals_lost"]);
  const SYSTEM_KINDS = new Set(["host_started", "host_stopped", "robot_online", "robot_offline"]);

  const app = {
    view: "home",
    settings: { clock: "24h", break_interval_min: 50 },
    today: null,          // today's stats, from the server
    shown: null,          // the day in History (YYYY-MM-DD); null = today
    history: [],
    lastEventId: 0,
    state: null,
    voice: null,          // voice snapshot
    voiceSettings: null,
    transcriptIds: new Set(),
    log: [],
    devices: [],
    scene: null,
  };

  // ------------------------------------------------------------------ helpers

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

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
  const longDate = (iso) => parseIso(iso).toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" });

  function dayLabel(iso) {
    const d = parseIso(iso), today = parseIso(app.today ? app.today.date : isoDate(new Date()));
    const diff = Math.round((today - d) / 86400000);
    if (diff === 0) return "Today";
    if (diff === 1) return "Yesterday";
    return longDate(iso);
  }

  async function api(path, options) {
    const r = await fetch(path, { credentials: "same-origin", cache: "no-store", ...options });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(body.error || `HTTP ${r.status}`);
    return body;
  }

  const post = (path, body = {}) => api(path, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  // ------------------------------------------------------------------ navigation

  function currentView() {
    let v = (location.hash || "#home").slice(1);
    if (!VIEWS.includes(v)) v = "home";
    if (v === "talk" && wide.matches) v = "home";          // the conversation is on Home
    return v;
  }

  function show(view, { focus = false } = {}) {
    app.view = view;
    document.body.dataset.view = view;
    for (const a of document.querySelectorAll(".tabs a")) {
      if (a.dataset.view === view) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
    }
    robotStream.update();
    if (view === "settings") loadSettings();
    if (view === "history") { loadWeek().catch(() => {}); if (app.shown) showDay(app.shown).catch(() => {}); else if (app.today) historyDay.render(app.today); }
    if (view === "talk" || (view === "home" && wide.matches)) scrollTranscript(true);
    if (focus) {
      const target = view === "talk" ? $("ask-input") : document.querySelector(`.view-${view} .view-title`) || $("main");
      if (target && !(view === "talk" && target.disabled)) target.focus({ preventScroll: true });
      window.scrollTo(0, 0);
    }
    if (view === "home" && app.today) todayCard.render(app.today);
  }

  function setupNav() {
    let byUser = false;
    for (const a of document.querySelectorAll(".tabs a")) a.addEventListener("click", () => { byUser = true; });
    window.addEventListener("hashchange", () => { show(currentView(), { focus: byUser }); byUser = false; });
    wide.addEventListener("change", () => show(currentView()));
    show(currentView());
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

  // ------------------------------------------------------------------ a day (Home: today, History: any day)

  class DayCard {
    constructor(root) {
      this.root = root;
      this.f = (name) => root.querySelector(`[data-f="${name}"]`);
      this.day = null;
    }

    render(d) {
      this.day = d;
      const isToday = app.today && d.date === app.today.date;
      const title = this.f("title");
      if (title) title.textContent = isToday ? "Today" : "Day";
      this.f("label").textContent = isToday || !title ? longDate(d.date) : dayLabel(d.date);
      this.f("seated").innerHTML = durationHTML(d.seated_s);
      this.f("sessions").textContent = d.sessions;
      this.f("breaks").textContent = d.breaks;
      this.f("longest").innerHTML = durationHTML(d.longest_s);
      const facts = [];
      if (d.first_arrival) facts.push(`First seen ${fmtTime(d.first_arrival)}`);
      if (d.last_departure) facts.push(`last left ${fmtTime(d.last_departure)}`);
      if (d.breath_rate != null) facts.push(`breathing ${Math.round(d.breath_rate)}/min`);
      if (d.heart_rate != null) facts.push(`heart ${Math.round(d.heart_rate)}/min on average`);
      if (!d.first_arrival) facts.push(isToday ? "Nobody at the desk yet today." : "Nobody at the desk that day.");
      this.f("facts").textContent = facts.join(" · ");
      this.timeline(d);
    }

    timeline(d) {
      const svg = this.f("timeline");
      if (!svg.getClientRects().length) return;           // not on screen: drawn when shown
      const dayStart = d.start, dayEnd = d.end;
      const tl = d.timeline;
      const first = tl.present.length ? tl.present[0][0] : null;
      const last = d.now || (tl.present.length ? tl.present[tl.present.length - 1][1] : null);
      const hourOf = (ts) => (ts - dayStart) / 3600;
      const h0 = Math.min(7, first != null ? Math.floor(hourOf(first)) : 7);
      let h1 = Math.max(19, last != null ? Math.ceil(hourOf(last) + 0.01) : 19);
      h1 = Math.min(h1, Math.round((dayEnd - dayStart) / 3600));
      const lo = dayStart + h0 * 3600, hi = dayStart + h1 * 3600;
      const W = 1000, H = 44, y = 14, h = 22;
      const x = (ts) => ((Math.min(Math.max(ts, lo), hi) - lo) / (hi - lo)) * W;
      const width = Math.max(1, svg.getBoundingClientRect().width || 600);

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
      const line = (cls, xx, y1, y2) => {
        const g = document.createElementNS(SVG, "line");
        g.setAttribute("class", cls);
        g.setAttribute("x1", xx); g.setAttribute("x2", xx);
        g.setAttribute("y1", y1); g.setAttribute("y2", y2);
        g.setAttribute("vector-effect", "non-scaling-stroke");
        svg.appendChild(g);
      };
      rect("tl-track", lo, hi, y, h, 5);
      const pxPerHour = width / (h1 - h0);
      const step = [1, 2, 3, 4, 6].find((k) => k * pxPerHour >= 58) || 6;
      const margin = 64 / pxPerHour;       // hours kept clear next to the first and last label
      for (let k = Math.ceil(h0 / step) * step; k <= h1; k += step) {
        if (k !== h0 && k !== h1) line("tl-grid", x(dayStart + k * 3600), y + h + 2, y + h + 6);
      }
      for (const [a, b] of tl.present) rect("tl-present", a, b);
      for (const [a, b] of tl.seated) rect("tl-seated", a, b, y + 3, h - 6, 2);
      for (const t of tl.reminders) {
        // an ellipse that stays round although the SVG stretches horizontally
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
      this.f("desc").textContent = `Timeline from ${fmtHour(h0)} to ${fmtHour(h1)}. ${parts.join(", ")}.`;
    }

    redraw() { if (this.day) this.timeline(this.day); }
  }

  const todayCard = new DayCard($("today"));
  const historyDay = new DayCard($("day"));

  function renderToday(d) {
    const newDay = app.today && app.today.date !== d.date;
    app.today = d;
    todayCard.render(d);
    if (!app.shown) historyDay.render(d);
    $("day-next").disabled = !app.shown;
    if (newDay) loadWeek().catch(() => {}); else renderWeek();
  }

  async function showDay(iso) {
    app.shown = !app.today || iso === app.today.date ? null : iso;
    if (!app.shown) { if (app.today) historyDay.render(app.today); }
    else historyDay.render(await api(`/api/day?date=${iso}`));
    $("day-next").disabled = !app.shown;
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
      const li = el("li");
      const btn = el("button");
      btn.type = "button";
      const isToday = app.today && d.date === app.today.date;
      if (isToday) btn.classList.add("today");
      btn.setAttribute("aria-pressed", String(d.date === shown));
      const date = parseIso(d.date);
      btn.setAttribute("aria-label", `${date.toLocaleDateString("en-GB", { weekday: "long" })}: seated ${fmtDuration(d.seated_s)}`);
      btn.append(el("span", "val", d.seated_s >= 60 ? fmtDuration(d.seated_s, { short: true }) : ""));
      const col = el("span", "col"), fill = el("span", "fill");
      fill.style.height = `${Math.max(2, (d.seated_s / max) * 100)}%`;
      col.append(fill);
      btn.append(col, el("span", "day", date.toLocaleDateString("en-GB", { weekday: "short" })));
      btn.addEventListener("click", () => showDay(d.date).catch(() => {}));
      li.appendChild(btn);
      ol.appendChild(li);
      if (!isToday && d.seated_s > 0) { sum += d.seated_s; n += 1; }
    }
    $("week-avg").textContent = n ? `${fmtDuration(sum / n)} a day on average` : "";
  }

  // ------------------------------------------------------------------ recent events (Home)

  function eventItem(e) {
    const li = el("li");
    li.dataset.id = e.id;
    const t = el("time", null, fmtTime(e.ts));
    t.dateTime = new Date(e.ts * 1000).toISOString();
    const p = el("span", null, e.text);
    if (SYSTEM_KINDS.has(e.kind)) p.className = "system";
    if (e.kind === "still_long") p.className = "attention";
    li.append(t, p);
    return li;
  }

  async function loadEvents() {
    const r = await api("/api/events?quiet=1&limit=8");
    $("events").replaceChildren(...r.events.map(eventItem));
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
      if (document.hidden || busy || !img.getClientRects().length) { schedule(1000); return; }
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

  // ------------------------------------------------------------------ conversation

  const LAT_LABELS = [
    ["endpoint", "end of speech"], ["queue", "backlog"], ["stt", "recognition"], ["llm_first_token", "model"],
    ["first_chunk", "first chunk"], ["tts", "synthesis"],
  ];

  function latencyText(e) {
    const lat = e.latency || {};
    const parts = [];
    if (e.first_word_s != null) parts.push(`first word ${e.first_word_s.toFixed(2)} s after you stopped`);
    for (const [k, label] of LAT_LABELS) {
      if (lat[k] == null || (k === "queue" && lat[k] < 0.05)) continue;
      parts.push(`${label} ${lat[k].toFixed(2)}${k === "stt" && lat.speculative ? " (speculative)" : ""}`);
    }
    return parts.join(" · ");
  }

  function transcriptItem(e) {
    if (e.kind === "ignored") {
      const li = el("li", "aside");
      li.append("Not answered: ");
      if (e.text) { li.append(el("q", null, e.text), " "); }
      li.append(el("span", null, `· ${e.reason}`));
      li.title = fmtTime(e.t);
      return li;
    }
    if (e.kind === "note") {
      const li = el("li", "aside note", e.text);
      li.title = fmtTime(e.t);
      return li;
    }
    const you = e.kind === "heard";
    const li = el("li", `msg ${you ? "you" : "marvin"}${e.proactive ? " proactive" : ""}`);
    li.append(el("p", "bubble", e.text));
    const meta = el("p", "meta");
    const time = el("time", null, fmtTime(e.t));
    time.dateTime = new Date(e.t * 1000).toISOString();
    meta.append(time);
    if (you && e.source === "typed") meta.append(el("span", null, "typed"));
    if (!you && e.proactive) meta.append(el("span", null, "Marvin spoke first"));
    if (!you && e.interrupted) meta.append(el("span", null, "interrupted"));
    li.append(meta);
    if (!you && e.first_word_s != null) {
      const btn = el("button", "lat-btn", `${e.first_word_s.toFixed(1)} s`);
      btn.type = "button";
      const detail = el("p", "lat-detail", latencyText(e));
      detail.hidden = true;
      detail.id = `lat-${e.id}`;
      btn.title = latencyText(e);
      btn.setAttribute("aria-expanded", "false");
      btn.setAttribute("aria-controls", detail.id);
      btn.setAttribute("aria-label", `Answered ${e.first_word_s.toFixed(1)} seconds after you stopped talking. Show details`);
      btn.addEventListener("click", () => {
        detail.hidden = !detail.hidden;
        btn.setAttribute("aria-expanded", String(!detail.hidden));
      });
      meta.append(btn);
      li.append(detail);
    }
    if (!you && e.error) {
      li.append(el("p", "err", e.hint || "The language model is not answering."));
    }
    return li;
  }

  function nearBottom() {
    const t = $("transcript");
    return t.scrollHeight - t.scrollTop - t.clientHeight < 80;
  }

  function scrollTranscript(force) {
    const t = $("transcript");
    if (force || nearBottom()) requestAnimationFrame(() => { t.scrollTop = t.scrollHeight; });
  }

  function addTranscript(e, { fresh = false } = {}) {
    if (app.transcriptIds.has(e.id)) return;
    app.transcriptIds.add(e.id);
    const stick = nearBottom();
    const li = transcriptItem(e);
    const ol = $("transcript");
    ol.appendChild(li);
    while (ol.children.length > 200) ol.firstElementChild.remove();
    $("transcript-empty").hidden = true;
    if (stick || fresh) scrollTranscript(true);
  }

  function renderVoice(v) {
    app.voice = v;
    const orb = $("orb");
    const on = v.state === "on";
    const status = on ? v.status : v.state === "unavailable" ? "off" : v.state;
    orb.dataset.status = status;
    orb.dataset.muted = String(!!v.muted);
    let text;
    if (v.state === "unavailable") text = "Voice needs marvin-host run";
    else if (v.state === "off") text = "Voice off";
    else if (v.state === "starting") text = "Starting, loading the models…";
    else if (v.state === "stopping") text = "Stopping…";
    else if (v.state === "error") text = "Voice unavailable";
    else if (v.muted && v.status === "idle") text = "Microphone muted";
    else text = {
      idle: v.wake ? "Say “Marvin, …”" : "Waiting for you to speak",
      listening: "Listening…", thinking: "Thinking…", speaking: "Speaking…",
    }[v.status] || v.status;
    $("voice-status").textContent = text;

    const sw = $("voice-switch");
    sw.checked = v.state === "on" || v.state === "starting";
    sw.disabled = v.state === "unavailable" || v.state === "stopping";

    const notice = $("voice-notice");
    notice.hidden = v.state !== "error";
    if (v.state === "error") {
      $("voice-notice-title").textContent = v.error;
      $("voice-notice-fix").hidden = !v.fix;
      $("voice-notice-code").textContent = v.fix || "";
    }
    for (const id of ["listen-now", "mute", "stop-speaking", "ask-input", "ask-send"]) $(id).disabled = !on;
    $("stop-speaking").disabled = !on || !(v.status === "thinking" || v.status === "speaking");
    $("mute").setAttribute("aria-pressed", String(!!v.muted));
    $("mute").title = v.muted ? "The microphone is muted: press to unmute" : "Mute the microphone";
    $("ask-input").placeholder = on ? "Ask Marvin something" : "Turn the voice on to ask Marvin";
    const empty = $("transcript-empty");
    if (!app.transcriptIds.size) {
      empty.hidden = false;
      empty.firstElementChild.textContent = on
        ? (v.wake ? "Say “Marvin, …” or type a question below." : "Just talk, or type a question below.")
        : v.state === "unavailable" ? "The conversation appears here when Marvin's voice runs." : "Turn the voice on to talk to Marvin.";
    }
    $("tab-talk-dot").hidden = !(on && (v.status === "listening" || v.status === "speaking"));
  }

  async function voiceCommand(path, body) {
    try {
      const r = await post(path, body);
      if (r.voice) renderVoice(r.voice);
    } catch (e) {
      addTranscript({ id: `local-${Date.now()}`, kind: "note", text: e.message, t: Date.now() / 1000 });
    }
  }

  function setupTalk() {
    $("voice-switch").addEventListener("change", (ev) => voiceCommand(ev.target.checked ? "/api/voice/on" : "/api/voice/off"));
    $("voice-retry").addEventListener("click", () => voiceCommand("/api/voice/on"));
    $("listen-now").addEventListener("click", () => voiceCommand("/api/voice/listen"));
    $("stop-speaking").addEventListener("click", () => voiceCommand("/api/voice/stop-speaking"));
    $("mute").addEventListener("click", () => voiceCommand("/api/voice/mute", { muted: !(app.voice && app.voice.muted) }));
    $("ask-form").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const input = $("ask-input");
      const text = input.value.trim();
      if (!text) return;
      input.value = "";
      await voiceCommand("/api/voice/ask", { text });
      input.focus();
    });
  }

  async function loadVoice() {
    const r = await api("/api/voice");
    app.voiceSettings = r.settings;
    for (const e of r.transcript) addTranscript(e);
    renderVoice(r.voice);
    scrollTranscript(true);
  }

  // ------------------------------------------------------------------ robot: devices

  function ago(s) {
    if (s < 1.5) return "live";
    if (s < 90) return `${Math.round(s)} s ago`;
    return `${Math.round(s / 60)} min ago`;
  }

  function uptime(s) {
    if (s == null) return "";
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    return h ? `up ${h} h ${String(m).padStart(2, "0")}` : `up ${m} min`;
  }

  function metric(label, value, unit, warn) {
    const d = el("div", warn ? "warn" : "");
    const dd = el("dd", null, value);
    if (unit) dd.append(el("small", null, unit));
    d.append(el("dt", null, label), dd);
    return d;
  }

  function deviceItem(d) {
    const li = el("li", "device");
    const head = el("div", "device-head");
    head.append(el("span", "device-name", d.label), el("span", "muted small", d.name));
    const link = el("span", `link ${!d.online ? "down" : d.age_s >= 3 ? "stale" : ""}`);
    link.append(el("span", "dot"), el("span", null, d.online ? ago(d.age_s) : `offline · ${ago(d.age_s)}`));
    if (d.rssi != null) {
      const bars = el("span", "bars");
      bars.setAttribute("role", "img");
      bars.setAttribute("aria-label", `Wi-Fi ${d.rssi} dBm, ${d.rssi_bars} of 4 bars`);
      for (let i = 1; i <= 4; i++) bars.append(el("i", i <= d.rssi_bars ? "on" : ""));
      link.append(bars, el("span", null, `${d.rssi} dBm`));
    }
    head.append(link);
    li.append(head);
    const sub = [d.board, d.firmware && `firmware ${d.firmware}`, d.ip, uptime(d.uptime_s)].filter(Boolean);
    li.append(el("p", "device-sub", sub.join(" · ")));
    const tags = el("div", "tags");
    if (d.simulated) tags.append(el("span", "tag", "Simulated data"));
    if (d.camera) tags.append(el("span", "tag", "Camera"));
    if (d.audio) tags.append(el("span", "tag", "Speaker and mic"));
    if (tags.children.length) li.append(tags);
    const r = d.rates || {};
    const m = el("dl", "metrics");
    m.append(metric("Datagrams", fmtRate(r.datagrams), "/s"));
    m.append(metric("Loss", d.loss_pct == null ? "–" : d.loss_pct.toFixed(1), "%", d.loss_pct != null && d.loss_pct >= 2));
    m.append(metric("CRC errors", String(d.crc_errors), "", false));
    if (d.role !== "vitals" || r.scans) m.append(metric("Lidar", fmtRate(r.scans), `/s · ${d.points} pts`));
    if (d.role !== "vitals" || r.radar) m.append(metric("Radar", fmtRate(r.radar), "/s"));
    if (d.role === "vitals" || r.vitals) m.append(metric("Vital signs", fmtRate(r.vitals), "/s"));
    if (d.shed) m.append(metric("Dropped by host", String(d.shed), "", true));
    li.append(m);
    return li;
  }

  const fmtRate = (x) => (x == null ? "–" : x >= 10 ? String(Math.round(x)) : x.toFixed(1));

  function renderDevices(list) {
    app.devices = list;
    $("devices-empty").hidden = list.length > 0;
    $("devices").replaceChildren(...list.map(deviceItem));
    const online = list.filter((d) => d.online).length;
    $("devices-summary").textContent = list.length ? `${online} of ${list.length} connected` : "";
  }

  // ------------------------------------------------------------------ robot: sensor mini-views (canvas)

  function canvas2d(c) {
    const r = c.getBoundingClientRect();
    if (!r.width) return null;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = Math.round(r.width * dpr), h = Math.round(r.height * dpr);
    if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
    const ctx = c.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, r.width, r.height);
    return { ctx, w: r.width, h: r.height };
  }

  function palette() {
    return { text: cssVar("--text"), muted: cssVar("--muted"), seated: cssVar("--seated"), accent: cssVar("--accent"),
             line: "rgba(236, 230, 218, 0.09)", lineStrong: "rgba(236, 230, 218, 0.18)", present: cssVar("--present") };
  }

  function rings(ctx, cx, cy, scale, meters, from, to, col) {
    ctx.lineWidth = 1;
    ctx.font = "11px system-ui, sans-serif";
    ctx.textAlign = "left";
    ctx.textBaseline = "middle";
    for (let k = 1; k <= meters; k++) {
      ctx.strokeStyle = col.line;
      ctx.beginPath();
      ctx.arc(cx, cy, k * 100 * scale, from, to);
      ctx.stroke();
    }
  }

  function drawPerson(ctx, x, y, col) {
    ctx.fillStyle = "rgba(238, 118, 38, 0.18)";
    ctx.beginPath(); ctx.arc(x, y, 12, 0, 2 * Math.PI); ctx.fill();
    ctx.fillStyle = col.accent;
    ctx.beginPath(); ctx.arc(x, y, 5.5, 0, 2 * Math.PI); ctx.fill();
  }

  function drawTrail(ctx, trail, toXY, col) {
    for (const [age, r, f] of trail) {
      const [x, y] = toXY(r, f);
      ctx.fillStyle = `rgba(238, 118, 38, ${Math.max(0.06, 0.45 * (1 - age / 5))})`;
      ctx.beginPath(); ctx.arc(x, y, 2.2, 0, 2 * Math.PI); ctx.fill();
    }
  }

  function drawLidar(scene) {
    const g = canvas2d($("lidar"));
    if (!g) return;
    const { ctx, w, h } = g, col = palette();
    const cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2 - 8;
    const lidar = scene && scene.lidar;
    const ranges = lidar ? lidar.ranges_cm : [];
    const nz = ranges.filter((d) => d > 0).sort((a, b) => a - b);
    const p95 = nz.length ? nz[Math.floor(nz.length * 0.95)] : 300;
    const meters = Math.max(2, Math.min(8, Math.ceil(p95 / 100)));
    const scale = R / (meters * 100);
    // the radar's field of view, faintly
    const fov = ((scene && scene.radar) || { half_angle_deg: 60 }).half_angle_deg * Math.PI / 180;
    ctx.fillStyle = "rgba(236, 230, 218, 0.035)";
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, R, -Math.PI / 2 - fov, -Math.PI / 2 + fov); ctx.closePath(); ctx.fill();
    rings(ctx, cx, cy, scale, meters, 0, 2 * Math.PI, col);
    ctx.fillStyle = col.muted;
    ctx.font = "11px system-ui, sans-serif";
    ctx.textAlign = "center";
    for (let k = 1; k <= meters; k++) if (k === meters || k % 2 === 0 || meters <= 4) ctx.fillText(`${k} m`, cx + k * 100 * scale * 0.7071 + 12, cy + k * 100 * scale * 0.7071 + 2);
    // the scan
    ctx.fillStyle = col.seated;
    ctx.globalAlpha = lidar && lidar.age_s > 2 ? 0.35 : 0.9;
    for (let i = 0; i < ranges.length; i++) {
      const d = ranges[i];
      if (!d) continue;
      const a = ((i + 0.5) * 360 / ranges.length) * Math.PI / 180;
      const rr = Math.min(R + 4, d * scale);
      ctx.fillRect(cx + Math.sin(a) * rr - 1.2, cy - Math.cos(a) * rr - 1.2, 2.4, 2.4);
    }
    ctx.globalAlpha = 1;
    // who the radar follows
    const toXY = (r, f) => [cx + r * scale, cy - f * scale];
    if (scene) drawTrail(ctx, scene.trail, toXY, col);
    if (scene) for (const [r, f] of scene.targets) drawPerson(ctx, ...toXY(r, f), col);
    // the robot, facing up
    ctx.fillStyle = col.text;
    ctx.beginPath(); ctx.moveTo(cx, cy - 9); ctx.lineTo(cx + 6.5, cy + 6); ctx.lineTo(cx - 6.5, cy + 6); ctx.closePath(); ctx.fill();
    $("lidar-meta").textContent = lidar ? `${lidar.points} points · ${meters} m` : "no scan yet";
  }

  function drawRadar(scene) {
    const g = canvas2d($("radar"));
    if (!g) return;
    const { ctx, w, h } = g, col = palette();
    const fovDeg = ((scene && scene.radar) || { half_angle_deg: 60 }).half_angle_deg;
    const fov = fovDeg * Math.PI / 180;
    const targets = scene ? scene.targets : [];
    const far = Math.max(0, ...targets.map(([r, f]) => Math.hypot(r, f)), ...(scene ? scene.trail.map(([, r, f]) => Math.hypot(r, f)) : [0]));
    const meters = Math.max(3, Math.min(6, Math.ceil(far / 100 + 0.5)));
    const cx = w / 2, cy = h - 14;
    const scale = Math.min((h - 30) / (meters * 100), (w / 2 - 12) / (Math.sin(fov) * meters * 100));
    const a0 = -Math.PI / 2 - fov, a1 = -Math.PI / 2 + fov;
    ctx.fillStyle = "rgba(236, 230, 218, 0.035)";
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, meters * 100 * scale, a0, a1); ctx.closePath(); ctx.fill();
    rings(ctx, cx, cy, scale, meters, a0, a1, col);
    ctx.strokeStyle = col.line;
    for (const deg of [-fovDeg, -fovDeg / 2, 0, fovDeg / 2, fovDeg]) {
      const a = -Math.PI / 2 + deg * Math.PI / 180;
      ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + Math.cos(a) * meters * 100 * scale, cy + Math.sin(a) * meters * 100 * scale); ctx.stroke();
    }
    ctx.fillStyle = col.muted;
    ctx.font = "11px system-ui, sans-serif";
    ctx.textAlign = "left";
    for (let k = 1; k <= meters; k++) {
      const rr = k * 100 * scale;      // along the right edge of the field of view, clear of the people
      ctx.fillText(`${k} m`, Math.min(w - 26, cx + Math.sin(fov) * rr + 6), cy - Math.cos(fov) * rr + 4);
    }
    const toXY = (r, f) => [cx + r * scale, cy - f * scale];
    if (scene) drawTrail(ctx, scene.trail, toXY, col);
    for (const [r, f] of targets) drawPerson(ctx, ...toXY(r, f), col);
    ctx.fillStyle = col.text;
    ctx.fillRect(cx - 9, cy + 2, 18, 4);
    if (targets.length) {
      const d = Math.min(...targets.map(([r, f]) => Math.hypot(r, f))) / 100;
      $("radar-meta").textContent = `${targets.length} ${targets.length === 1 ? "person" : "people"} · ${d.toFixed(2)} m`;
    } else {
      $("radar-meta").textContent = scene ? "nobody in view" : "no frame yet";
    }
  }

  function drawSpark(c, series, lo, hi) {
    const g = canvas2d(c);
    if (!g) return;
    const { ctx, w, h } = g, col = palette();
    ctx.strokeStyle = col.line;
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(0, h - 0.5); ctx.lineTo(w, h - 0.5); ctx.stroke();
    const vals = series.filter((p) => p[1] != null).map((p) => p[1]);
    if (!vals.length) return;
    let a = Math.min(...vals), b = Math.max(...vals);
    const mid = (a + b) / 2, span = Math.max(b - a, hi - lo);
    a = mid - span / 2; b = mid + span / 2;
    const x = (age) => w - (age / 300) * w;
    const y = (v) => 6 + (1 - (v - a) / (b - a)) * (h - 14);
    ctx.strokeStyle = col.seated;
    ctx.lineWidth = 1.6;
    ctx.lineJoin = "round";
    ctx.beginPath();
    let pen = false, last = null;
    for (const [age, v] of series.slice().sort((p, q) => q[0] - p[0])) {
      if (v == null) { pen = false; continue; }
      if (pen) ctx.lineTo(x(age), y(v)); else ctx.moveTo(x(age), y(v));
      pen = true; last = [age, v];
    }
    ctx.stroke();
    if (last) {
      ctx.fillStyle = col.text;
      ctx.beginPath(); ctx.arc(x(last[0]), y(last[1]), 2.6, 0, 2 * Math.PI); ctx.fill();
    }
    ctx.fillStyle = col.muted;
    ctx.font = "10px system-ui, sans-serif";
    ctx.textAlign = "left";
    ctx.fillText(`${Math.round(Math.max(...vals))}`, 2, 10);
    ctx.fillText(`${Math.round(Math.min(...vals))}`, 2, h - 4);
  }

  function drawWave(c, waves, k) {
    const g = canvas2d(c);
    if (!g) return;
    const { ctx, w, h } = g, col = palette();
    const mid = h / 2;
    ctx.strokeStyle = waves.length ? col.muted : col.line;
    ctx.lineWidth = 1.3;
    ctx.beginPath();
    if (!waves.length) { ctx.moveTo(0, mid); ctx.lineTo(w, mid); ctx.stroke(); return; }
    const pts = waves.slice().sort((p, q) => q[0] - p[0]);
    pts.forEach((p, i) => {
      const x = w - (p[0] / 15) * w, y = mid - p[k] * (h / 2 - 3);
      if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y);
    });
    ctx.stroke();
  }

  let vitalsHistory = [];
  function drawVitals(scene) {
    const v = scene && scene.vitals;
    if (v && v.rates) vitalsHistory = v.rates;
    const set = (id, val) => {
      const e = $(id);
      e.replaceChildren();
      if (val == null) { e.textContent = "–"; return; }
      e.append(String(Math.round(val)), el("small", null, "/min"));
    };
    set("vit-breath", v ? v.breath_rate : null);
    set("vit-heart", v ? v.heart_rate : null);
    $("vit-meta").textContent = !v ? "no radar yet" : v.valid ? (v.distance_cm ? `measured at ${(v.distance_cm / 100).toFixed(2)} m` : "measured") : "not readable now";
    drawSpark($("spark-breath"), vitalsHistory.map((p) => [p[0], p[1]]), 10, 18);
    drawSpark($("spark-heart"), vitalsHistory.map((p) => [p[0], p[2]]), 55, 80);
    drawWave($("wave-breath"), v ? v.waves : [], 1);
    drawWave($("wave-heart"), v ? v.waves : [], 2);
  }

  function drawScene() {
    drawLidar(app.scene);
    drawRadar(app.scene);
    drawVitals(app.scene);
  }

  // the Robot panel's own stream: only while it is on screen
  const robotStream = {
    es: null,
    frame: 0,
    update() {
      const want = app.view === "robot" && !document.hidden;
      if (want && !this.es) {
        this.es = new EventSource("/api/robot/stream");
        this.es.addEventListener("scene", (m) => {
          app.scene = JSON.parse(m.data);
          if (!this.frame) this.frame = requestAnimationFrame(() => { this.frame = 0; drawScene(); });
        });
        this.es.addEventListener("devices", (m) => renderDevices(JSON.parse(m.data)));
        api("/api/robot").then((r) => { renderDevices(r.devices); app.scene = r.scene; drawScene(); }).catch(() => {});
        loadLog().catch(() => {});
      } else if (!want && this.es) {
        this.es.close();
        this.es = null;
      }
    },
  };

  // ------------------------------------------------------------------ log

  const SOURCE_LABEL = { brain: "Marvin", device: "Device", host: "Host", voice: "Voice" };

  function logFilter() {
    const v = document.querySelector('input[name="log-filter"]:checked').value;
    return (e) => !v || (v === "warning" ? (e.level === "warning" || e.level === "error")
      : v === "brain" ? (e.source === "brain" || e.source === "host") : e.source === v);
  }

  function logItem(e) {
    const li = el("li");
    const t = el("time", null, fmtTime(e.ts));
    t.dateTime = new Date(e.ts * 1000).toISOString();
    const dev = e.source === "device" && e.device;
    const src = el("span", dev ? "src dev" : "src", dev ? e.device : SOURCE_LABEL[e.source] || e.source);
    const text = el("span", `text ${e.level === "attention" ? "attention" : e.level === "warning" || e.level === "error" ? e.level : ""}`, e.text);
    li.append(t, src, text);
    return li;
  }

  function renderLog() {
    const keep = logFilter();
    const items = app.log.filter(keep).slice(0, 200);
    $("log").replaceChildren(...items.map(logItem));
    $("log-empty").hidden = items.length > 0;
  }

  async function loadLog() {
    const r = await api("/api/log?limit=300");
    app.log = r.entries;
    renderLog();
  }

  function addLog(e) {
    if (app.log.length && app.log[0].id >= e.id) return;
    app.log.unshift(e);
    if (app.log.length > 500) app.log.pop();
    if (app.view !== "robot" || !logFilter()(e)) return;
    const li = logItem(e);
    li.classList.add("fresh");
    $("log").prepend(li);
    while ($("log").children.length > 200) $("log").lastElementChild.remove();
    $("log-empty").hidden = true;
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
    es.addEventListener("today", (m) => renderToday(JSON.parse(m.data)));
    es.addEventListener("event", (m) => addEvent(JSON.parse(m.data)));
    es.addEventListener("settings", (m) => applySettings(JSON.parse(m.data)));
    es.addEventListener("voice", (m) => renderVoice(JSON.parse(m.data)));
    es.addEventListener("transcript", (m) => addTranscript(JSON.parse(m.data), { fresh: true }));
    es.addEventListener("log", (m) => addLog(JSON.parse(m.data)));
    es.addEventListener("devices", (m) => { if (!robotStream.es) renderDevices(JSON.parse(m.data)); });
  }

  // ------------------------------------------------------------------ settings

  function applySettings(s) {
    const clockChanged = s.clock !== app.settings.clock;
    app.settings = s;
    if (clockChanged) {
      if (app.today) todayCard.render(app.today);
      historyDay.redraw();
      loadEvents().catch(() => {});
    }
  }

  function flash(id, text) {
    const e = $(id);
    e.textContent = text;
    clearTimeout(e._t);
    e._t = setTimeout(() => { e.textContent = ""; }, 4000);
  }

  const STT_LABELS = { auto: "Auto", mlx: "MLX (Apple GPU)", "faster-whisper": "faster-whisper (CPU)" };
  const TTS_LABELS = { auto: "Auto (best available)", say: "macOS say", piper: "Piper", espeak: "espeak-ng" };

  function fillSelect(sel, options, value) {
    sel.replaceChildren();
    for (const o of options) {
      const opt = el("option", null, o.label);
      opt.value = o.value;
      if (o.disabled) opt.disabled = true;
      sel.append(opt);
    }
    if (value != null && ![...sel.options].some((o) => o.value === value)) {
      const opt = el("option", null, value);
      opt.value = value;
      sel.append(opt);
    }
    sel.value = value == null ? "" : value;
  }

  let voiceOptions = null;
  function fillVoices() {
    const tts = $("v-tts").value;
    const cur = app.voiceSettings ? app.voiceSettings.tts_voice : null;
    const opts = [{ value: "", label: "Default" }];
    const o = voiceOptions || { voices: { piper: [], say: [] }, tts_backends: [] };
    const piperOk = (o.tts_backends.find((b) => b.name === "piper") || {}).available;
    const listPiper = tts === "piper" || (tts === "auto" && piperOk);
    if (listPiper) for (const v of o.voices.piper) opts.push({ value: v.name, label: v.installed ? v.name : `${v.name} (downloads once)` });
    if (tts === "say" || (tts === "auto" && !piperOk)) for (const v of o.voices.say) opts.push({ value: v.name, label: `${v.name} (${v.locale})` });
    fillSelect($("v-voice"), opts, cur || "");
  }

  function fillVoiceForm(s) {
    const o = voiceOptions;
    const form = $("voice-form");
    const available = !!s;
    $("voice-fields").disabled = !available;
    form.querySelector('button[type="submit"]').disabled = !available;
    if (!available) {
      $("voice-form-note").textContent = "Voice settings are available when the app runs with marvin-host run.";
      return;
    }
    $("v-llm").value = s.llm_model;
    const dl = $("v-llm-list");
    dl.replaceChildren(...(o ? o.llm_models : []).map((n) => { const opt = el("option"); opt.value = n; return opt; }));
    const hint = $("v-llm-hint");
    if (!o) hint.textContent = "Models installed in Ollama.";
    else if (!o.ollama.ok) hint.textContent = `${o.ollama.error}. ${o.ollama.fix}`;
    else if (o.llm_models.length && !o.llm_models.includes(s.llm_model) && !o.llm_models.includes(`${s.llm_model}:latest`)) hint.textContent = `Not installed yet: ollama pull ${s.llm_model}`;
    else hint.textContent = `${o.llm_models.length} model${o.llm_models.length === 1 ? "" : "s"} installed in Ollama.`;
    fillSelect($("v-stt"), (o ? o.stt_backends : ["auto", "mlx", "faster-whisper"]).map((b) => ({ value: b, label: STT_LABELS[b] || b })), s.stt);
    fillSelect($("v-stt-model"), (o ? o.stt_models : ["auto"]).map((m) => ({ value: m === "auto" ? "" : m, label: m === "auto" ? "Default" : m })), s.stt_model || "");
    fillSelect($("v-tts"), (o ? o.tts_backends : [{ name: "auto", available: true }]).map((b) => ({ value: b.name, label: (TTS_LABELS[b.name] || b.name) + (b.available ? "" : " (not installed)"), disabled: !b.available && b.name !== s.tts })), s.tts);
    fillVoices();
    form.querySelector(`input[name="v-lang"][value="${s.language || "auto"}"]`).checked = true;
    $("v-wake").checked = s.wake;
    $("v-follow").value = s.follow_up_s;
    $("v-reminders").checked = s.reminders;
  }

  async function loadSettings() {
    try {
      const r = await api("/api/settings");
      const s = r.settings, form = $("settings-form");
      $("f-break").value = s.break_interval_min;
      $("f-quiet").checked = s.quiet_hours.enabled;
      $("f-quiet-start").value = s.quiet_hours.start;
      $("f-quiet-end").value = s.quiet_hours.end;
      form.querySelector(`input[name="clock"][value="${s.clock}"]`).checked = true;
      syncQuiet();
      const about = $("about-data");
      about.replaceChildren();
      if (r.about.data_dir) {
        about.append("History and settings are stored on this computer only, in ", el("code", null, r.about.data_dir),
          ". Delete that folder to erase them. The voice's settings are in voice.json, next to calibration.json.");
      } else {
        about.textContent = "This is a demo: nothing is kept.";
      }
    } catch (e) { /* keep the form as it is */ }
    try {
      const v = await api("/api/voice");
      app.voiceSettings = v.settings;
      fillVoiceForm(v.settings);
      if (v.settings) {
        voiceOptions = await api("/api/voice/options");
        fillVoiceForm(app.voiceSettings);
      }
    } catch (e) { /* voice settings stay as they are */ }
  }

  function syncQuiet() {
    $("f-quiet-start").disabled = $("f-quiet-end").disabled = !$("f-quiet").checked;
  }

  function setupSettings() {
    $("f-quiet").addEventListener("change", syncQuiet);
    $("v-tts").addEventListener("change", fillVoices);
    $("settings-form").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const err = $("settings-error");
      err.hidden = true;
      const form = ev.target;
      const body = {
        break_interval_min: Number($("f-break").value),
        quiet_hours: { enabled: $("f-quiet").checked, start: $("f-quiet-start").value || "22:00", end: $("f-quiet-end").value || "07:00" },
        clock: form.querySelector('input[name="clock"]:checked').value,
      };
      try {
        const r = await post("/api/settings", body);
        applySettings(r.settings);
        flash("settings-saved", "Saved");
      } catch (e) {
        err.textContent = e.message;
        err.hidden = false;
      }
    });
    $("voice-form").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const err = $("voice-error");
      err.hidden = true;
      const form = ev.target;
      const body = {
        llm_model: $("v-llm").value.trim(),
        stt: $("v-stt").value,
        stt_model: $("v-stt-model").value || "auto",
        tts: $("v-tts").value,
        tts_voice: $("v-voice").value,
        language: form.querySelector('input[name="v-lang"]:checked').value,
        wake: $("v-wake").checked,
        follow_up_s: Number($("v-follow").value),
        reminders: $("v-reminders").checked,
      };
      try {
        const r = await post("/api/voice/settings", body);
        app.voiceSettings = r.settings;
        renderVoice(r.voice);
        const running = r.voice.state === "on" || r.voice.state === "starting";
        flash("voice-saved", running ? "Saved. Marvin's voice restarts." : "Saved");
      } catch (e) {
        err.textContent = e.message;
        err.hidden = false;
      }
    });
  }

  // ------------------------------------------------------------------ start

  async function init() {
    setupSettings();
    setupTalk();
    for (const r of document.querySelectorAll('input[name="log-filter"]')) r.addEventListener("change", renderLog);
    $("day-prev").addEventListener("click", () => shiftDay(-1));
    $("day-next").addEventListener("click", () => shiftDay(1));
    document.addEventListener("visibilitychange", () => robotStream.update());
    startFace();
    try {
      const r = await api("/api/state");
      app.settings = r.settings;
      renderState(r.state);
      renderToday(r.today);
    } catch (e) {
      $("status").textContent = "Cannot reach Marvin";
    }
    setupNav();
    await Promise.all([loadWeek(), loadEvents(), loadVoice(), loadLog()]).catch(() => {});
    connect();
    let resizeTimer;
    window.addEventListener("resize", () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        todayCard.redraw();
        historyDay.redraw();
        if (app.view === "robot") drawScene();
      }, 150);
    });
  }

  init();
})();

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
    talkNowAt: 0,         // when this page pressed Talk now (performance.now())
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
    if (view === "history") {
      loadWeek().catch(() => {});
      if (app.shown) showDay(app.shown).catch(() => {});
      else if (app.today) { historyDay.render(app.today); conversations.load(app.today.date).catch(() => {}); }
    }
    if (view === "talk" || (view === "home" && wide.matches)) { scrollTranscript(true); fitNow(); }
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
    $("sim-badge").hidden = !s.simulated;
    renderNow(s);
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
    conversations.load(iso).catch(() => {});
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

  // ------------------------------------------------------------------ conversations (History)
  // The conversation of the day shown above (the same bubbles as Talk, read-only, answers still
  // open their inspector), and a search over everything said, kept on this computer.

  const conversations = {
    day: null,
    pending: null,          // entry id to show once its day is loaded (a search result)
    seq: 0,
    timer: 0,

    setup() {
      const q = $("convo-q");
      $("convo-search").addEventListener("submit", (ev) => { ev.preventDefault(); this.search(q.value); });
      q.addEventListener("input", () => {
        clearTimeout(this.timer);
        this.timer = setTimeout(() => this.search(q.value), 250);
      });
      q.addEventListener("keydown", (ev) => { if (ev.key === "Escape" && q.value) { q.value = ""; this.search(""); } });
    },

    async load(iso) {
      const seq = ++this.seq;
      const r = await api(`/api/conversation?day=${iso}`);
      if (seq !== this.seq) return;
      this.day = r.day;
      $("convo-day").textContent = dayLabel(r.day);
      const b = new ConvoBuilder();
      const items = [];
      for (const e of r.entries) {
        const { li, grouped } = b.item(e);
        if (!grouped) items.push(li);
      }
      $("convo").replaceChildren(...items);
      if (!$("convo-q").value.trim()) this.showResults(false);
      $("convo-empty").hidden = r.entries.length > 0 || !$("convo-results").hidden;
      $("convo-empty").textContent = app.today && r.day === app.today.date ? "No conversation yet today." : "No conversation that day.";
      if (this.pending != null) {
        const id = this.pending;
        this.pending = null;
        const li = $("convo").querySelector(`[data-id="${id}"]`);
        if (li) {
          li.scrollIntoView({ block: "center", behavior: reduceMotion.matches ? "auto" : "smooth" });
          li.classList.add("found");
          setTimeout(() => li.classList.remove("found"), 2400);
        }
      }
    },

    // a new entry while History shows today: reload it (at most every 2 s)
    refreshToday() {
      if (app.shown || !app.today || this.refreshing) return;
      this.refreshing = setTimeout(() => { this.refreshing = 0; this.load(app.today.date).catch(() => {}); }, 2000);
    },

    showResults(on) {
      $("convo-results").hidden = !on;
      $("convo").hidden = on;
      if (!on) $("convo-count").textContent = "";
    },

    async search(text) {
      const q = text.trim();
      const seq = ++this.seq;
      if (q.length < 2) {
        this.showResults(false);
        $("convo-empty").hidden = $("convo").children.length > 0;
        return;
      }
      const r = await api(`/api/conversation?q=${encodeURIComponent(q)}`);
      if (seq !== this.seq) return;
      this.showResults(true);
      $("convo-empty").hidden = true;
      $("convo-count").textContent = r.results.length ? `${plural(r.results.length, "line", "lines")}${r.results.length >= 100 ? " (the latest 100)" : ""}` : `Nothing said matches “${q}”.`;
      $("convo-results").replaceChildren(...r.results.map((e) => this.result(e, q)));
    },

    result(e, q) {
      const li = el("li");
      const btn = el("button", `convo-hit ${e.kind === "heard" ? "you" : "marvin"}`);
      btn.type = "button";
      const d = new Date(e.t * 1000);
      const when = el("time", null, `${d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" })} · ${fmtTime(e.t)}`);
      when.dateTime = d.toISOString();
      const who = el("span", "hit-who", e.kind === "heard" ? "You" : "Marvin");
      const text = el("span", "hit-text");
      const lower = e.text.toLowerCase(), k = lower.indexOf(q.toLowerCase());
      if (k >= 0) {
        const start = Math.max(0, k - 60);
        text.append((start ? "…" : "") + e.text.slice(start, k), el("mark", null, e.text.slice(k, k + q.length)), e.text.slice(k + q.length));
      } else text.textContent = e.text;
      btn.append(when, who, text);
      btn.addEventListener("click", () => {
        this.pending = e.id;
        $("convo-q").value = "";
        this.showResults(false);
        showDay(isoDate(d)).catch(() => {});
        $("day").scrollIntoView({ block: "start", behavior: reduceMotion.matches ? "auto" : "smooth" });
      });
      li.append(btn);
      return li;
    },
  };

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
  // One entry per line of the conversation (voice/control.py): "heard", "reply", "ignored", "note".
  // The same code draws the live chat (Talk) and past days (History, read-only). Each answer can
  // open an inspector ("why did Marvin say that"): what was heard, what Marvin knew (the context
  // block sent with the question), where the time went, the model and the language. Consecutive
  // ignored entries fold into one discreet line that opens to show each one and why.

  const LANG_NAMES = { fr: "French", en: "English", de: "German", es: "Spanish", it: "Italian", nl: "Dutch", pt: "Portuguese" };
  // the stages between the end of your question and Marvin's first word, in order
  const STAGES = [
    { key: "endpoint", label: "End of speech", hint: "the silence that tells Marvin you finished" },
    { key: "queue", label: "Backlog", hint: "audio waiting to be heard" },
    { key: "stt", label: "Recognition", hint: "speech to text" },
    { key: "llm_first_token", label: "Model", hint: "until the model's first word" },
    { key: "tools", label: "Tools", hint: "running the tools the model asked for", tool: true },
    { key: "llm_first_token_2", label: "Model (again)", hint: "until the model's first word, with the tools' results" },
    { key: "first_chunk", label: "First sentence", hint: "until the model finished a first clause" },
    { key: "tts", label: "Synthesis", hint: "turning it into speech" },
  ];

  const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
  const fmtSeconds = (x) => `${x < 10 ? x.toFixed(2) : x.toFixed(1)} s`;

  // Durations of the stages drawn in the timing bar (seconds, in order, only those that took time)
  function timingSegments(e) {
    const lat = e.latency || {};
    const out = [];
    const add = (key, v) => { if (v != null && v >= 0.005) out.push({ ...STAGES.find((st) => st.key === key), v }); };
    add("endpoint", lat.endpoint);
    if (lat.queue >= 0.05) add("queue", lat.queue);
    add("stt", lat.stt);
    add("llm_first_token", lat.llm_first_token);
    add("tools", lat.tools);
    add("llm_first_token_2", lat.llm_first_token_2);
    const before = (lat.llm_first_token || 0) + (lat.tools || 0) + (lat.llm_first_token_2 || 0);
    if (lat.first_chunk != null) add("first_chunk", lat.first_chunk - before);
    add("tts", lat.tts);
    return out;
  }

  // "- fact" lines of the context block sent with the question (voice/persona.py)
  function contextFacts(ctx) {
    return (ctx || "").split("\n").filter((l) => l.startsWith("- ")).map((l) => l.slice(2).trim());
  }

  function section(title) {
    const sec = el("section", "insp-sec");
    sec.append(el("h3", null, title));
    return sec;
  }

  // "get_weather(place: "Nice", day: "now")"
  function toolCallText(c) {
    const args = Object.entries(c.arguments || {}).map(([k, v]) => `${k}: ${JSON.stringify(v)}`);
    return `${c.name}(${args.join(", ")})`;
  }

  // units written in result keys (voice/tools): "temperature_c" reads "temperature 21 °C"
  const UNIT_SUFFIXES = [["_c", "°C"], ["_kmh", "km/h"], ["_mm", "mm"], ["_percent", "%"], ["_s", "s"]];

  // a tool's result, for reading: JSON objects as "key value · key value", with their units
  function toolResultText(text) {
    try {
      const o = JSON.parse(text);
      if (o && typeof o === "object" && !Array.isArray(o)) {
        return Object.entries(o).map(([k, v]) => {
          const u = UNIT_SUFFIXES.find(([suf]) => k.endsWith(suf) && typeof v === "number");
          const label = (u ? k.slice(0, -u[0].length) : k).replace(/_/g, " ");
          const value = typeof v === "object" ? JSON.stringify(v) : String(v);
          return `${label} ${value}${u ? ` ${u[1]}` : ""}`;
        }).join(" · ");
      }
    } catch (err) { /* plain text */ }
    return text;
  }

  function toolsSection(calls, fillerAt) {
    const sec = section(calls.length === 1 ? "Tool used" : `Tools used (${calls.length})`);
    const ul = el("ul", "insp-tools");
    for (const c of calls) {
      const li = el("li", "insp-tool");
      li.append(el("code", null, toolCallText(c)), el("span", "dur", c.seconds != null ? fmtSeconds(c.seconds) : ""));
      li.append(c.ok === false ? el("p", "res bad", `Error: ${c.error || "failed"}`) : el("p", "res", toolResultText(c.result || "")));
      ul.append(li);
    }
    sec.append(ul);
    if (fillerAt != null) sec.append(el("p", "insp-total", "Marvin said a few words while the tool ran, so there was no silence."));
    return sec;
  }

  function inspectorFor(e) {
    const box = el("div", "inspect");
    box.setAttribute("role", "region");
    box.setAttribute("aria-label", "Why Marvin said that");
    const heard = e._heard;
    if (heard) {
      const sec = section(heard.source === "typed" ? "You typed" : "Marvin heard");
      sec.append(el("p", "insp-quote", heard.text));
      if (heard.raw && heard.raw !== heard.text) {
        const raw = el("p", "insp-raw");
        raw.append(el("span", "muted", "Transcript: "), el("q", null, heard.raw));
        sec.append(raw);
      }
      box.append(sec);
    }
    const segs = timingSegments(e);
    if (segs.length) {
      const sec = section("Where the time went");
      const total = e.first_word_s != null ? e.first_word_s : segs.reduce((a, x) => a + x.v, 0);
      const bar = el("div", "tbar");
      bar.setAttribute("role", "img");
      bar.setAttribute("aria-label", segs.map((x) => `${x.label} ${fmtSeconds(x.v)}`).join(", "));
      const sum = Math.max(total, segs.reduce((a, x) => a + x.v, 0));
      // the model's stages keep the grey ramp (lighter = later), tools stand apart
      let shade = 0;
      const cls = segs.map((x) => (x.tool ? "tool" : `t${shade++}`));
      segs.forEach((x, i) => {
        const seg = el("span", `tseg ${cls[i]}`);
        seg.style.flexGrow = String(Math.max(0.001, x.v / sum));
        seg.title = `${x.label}: ${fmtSeconds(x.v)} (${x.hint})`;
        bar.append(seg);
      });
      const rest = total - segs.reduce((a, x) => a + x.v, 0);
      if (rest > 0.02) { const r = el("span", "tseg rest"); r.style.flexGrow = String(rest / sum); r.title = `Other: ${fmtSeconds(rest)}`; bar.append(r); }
      const legend = el("ul", "tlegend");
      segs.forEach((x, i) => {
        const li = el("li");
        li.append(el("i", `sw ${cls[i]}`), el("span", null, x.label), el("b", null, fmtSeconds(x.v)));
        li.title = x.hint;
        legend.append(li);
      });
      if (rest > 0.02) {
        const li = el("li");
        li.append(el("i", "sw rest"), el("span", null, "Other"), el("b", null, fmtSeconds(rest)));
        li.title = "audio queued before it plays, and the rest";
        legend.append(li);
      }
      sec.append(bar, legend);
      if (e.first_word_s != null) {
        sec.append(el("p", "insp-total", `First word ${fmtSeconds(e.first_word_s)} after ${heard && heard.source === "typed" ? "you sent it" : "you stopped talking"}${(e.latency || {}).speculative ? " · recognition started during your pause" : ""}`));
      }
      box.append(sec);
    }
    if (e.tools && e.tools.length) box.append(toolsSection(e.tools, (e.latency || {}).filler_start));
    const facts = contextFacts(e.context);
    if (facts.length) {
      const sec = section("What Marvin knew");
      const ul = el("ul", "insp-facts");
      for (const f of facts) ul.append(el("li", null, f));
      sec.append(ul);
      box.append(sec);
    }
    const foot = [e.model, LANG_NAMES[e.language] || e.language].filter(Boolean);
    if (e.interrupted) foot.push("interrupted");
    if (foot.length) box.append(el("p", "insp-foot", foot.join(" · ")));
    if (e.prompt) {
      const d = el("details", "insp-prompt");
      d.append(el("summary", null, "Exact message sent to the model"), el("pre", null, e.prompt));
      box.append(d);
    }
    if (!box.children.length) box.append(el("p", "insp-foot", e.proactive ? "Marvin said this on his own, without the language model." : "Nothing more is known about this answer."));
    return box;
  }

  let inspectorSeq = 0;
  // a button (and, with a pointer, the bubble) that opens the inspector under the answer
  function attachInspector(li, bubble, btn, e) {
    let box = null;
    const id = `insp-${++inspectorSeq}`;
    btn.setAttribute("aria-expanded", "false");
    btn.setAttribute("aria-controls", id);
    const toggle = () => {
      if (!box) { box = inspectorFor(e); box.id = id; box.hidden = true; li.append(box); }
      const open = box.hidden;
      box.hidden = !open;
      li.classList.toggle("inspecting", open);
      btn.setAttribute("aria-expanded", String(open));
      const list = li.closest(".transcript");
      if (open && list) {
        // the answer at the top of the conversation, its inspector below it; stay there
        follow = false;
        requestAnimationFrame(() => {
          const below = box.getBoundingClientRect().bottom - list.getBoundingClientRect().bottom;
          if (below <= 0) return;
          const top = list.scrollTop + li.getBoundingClientRect().top - list.getBoundingClientRect().top - 12;
          list.scrollTo({ top: Math.min(top, list.scrollTop + below + 12), behavior: reduceMotion.matches ? "auto" : "smooth" });
        });
      }
    };
    btn.addEventListener("click", toggle);
    bubble.classList.add("tappable");
    bubble.addEventListener("click", () => { if (!String(window.getSelection() || "")) toggle(); });
  }

  function transcriptItem(e) {
    if (e.kind === "note") {
      const li = el("li", "aside note", e.text);
      li.title = fmtTime(e.t);
      li.dataset.id = e.id;
      return li;
    }
    const you = e.kind === "heard";
    const li = el("li", `msg ${you ? "you" : "marvin"}${e.proactive ? " proactive" : ""}`);
    li.dataset.id = e.id;
    const bubble = el("p", "bubble", e.text);
    li.append(bubble);
    const meta = el("p", "meta");
    const time = el("time", null, fmtTime(e.t));
    time.dateTime = new Date(e.t * 1000).toISOString();
    meta.append(time);
    if (you && e.source === "typed") meta.append(el("span", null, "typed"));
    if (!you && e.proactive) meta.append(el("span", null, "Marvin spoke first"));
    if (!you && e.interrupted) meta.append(el("span", null, "interrupted"));
    li.append(meta);
    if (!you && (e.context || e.prompt || e.first_word_s != null || e.model)) {
      const btn = el("button", "insp-btn");
      btn.type = "button";
      btn.append(el("span", null, e.first_word_s != null ? fmtSeconds(e.first_word_s).replace(".00 s", " s") : "Details"));
      btn.insertAdjacentHTML("beforeend", '<svg class="icon xs" aria-hidden="true"><use href="#i-chevron"/></svg>');
      btn.setAttribute("aria-label", e.first_word_s != null
        ? `Answered ${e.first_word_s.toFixed(1)} seconds after you stopped. Why Marvin said that`
        : "Why Marvin said that");
      btn.title = "Why Marvin said that";
      meta.append(btn);
      attachInspector(li, bubble, btn, e);
    }
    if (!you && e.error) {
      li.append(el("p", "err", e.hint || "The language model is not answering."));
    }
    return li;
  }

  // ---- ignored entries, folded

  function ignoredSummary(list) {
    if (list.length === 1) {
      const e = list[0];
      return e.text ? `Not answered: “${e.text}”` : "A sound, ignored";
    }
    const sounds = list.filter((e) => !e.text).length, sentences = list.length - sounds;
    if (!sentences) return `${plural(sounds, "sound", "sounds")} ignored`;
    if (!sounds) return `${plural(sentences, "sentence", "sentences")} not answered`;
    return `${plural(sentences, "sentence", "sentences")} and ${plural(sounds, "sound", "sounds")} not answered`;
  }

  function ignoredLine(e) {
    const li = el("li", "ig-item");
    li.dataset.id = e.id;
    const t = el("time", null, fmtTime(e.t));
    t.dateTime = new Date(e.t * 1000).toISOString();
    const what = e.text ? el("q", null, e.text) : el("span", "ig-sound", "a sound");
    const why = el("span", "ig-why", e.reason || "not answered");
    li.append(t, what, why);
    if (e.dbfs != null) li.append(el("span", "ig-level", `${Math.round(e.dbfs)} dBFS`.replace("-", "−")));
    return li;
  }

  class IgnoredGroup {
    constructor(first) {
      this.entries = [];
      this.li = el("li", "aside ig");
      this.li.dataset.id = first.id;
      this.btn = el("button", "ig-sum");
      this.btn.type = "button";
      this.label = el("span", "ig-label");
      this.reason = el("span", "ig-reason");
      this.btn.append(this.label, this.reason);
      this.btn.insertAdjacentHTML("beforeend", '<svg class="icon xs ig-chev" aria-hidden="true"><use href="#i-chevron"/></svg>');
      this.list = el("ol", "ig-list");
      this.list.id = `ig-${++inspectorSeq}`;
      this.list.hidden = true;
      this.btn.setAttribute("aria-expanded", "false");
      this.btn.setAttribute("aria-controls", this.list.id);
      this.btn.addEventListener("click", () => {
        this.list.hidden = !this.list.hidden;
        this.btn.setAttribute("aria-expanded", String(!this.list.hidden));
        this.li.classList.toggle("open", !this.list.hidden);
      });
      this.li.append(this.btn, this.list);
      this.add(first, false);
    }

    add(e, grow = true) {
      this.entries.push(e);
      this.list.append(ignoredLine(e));
      this.label.textContent = ignoredSummary(this.entries);
      this.reason.textContent = this.entries.length === 1 ? ` · ${e.reason || "not answered"}` : "";
      this.btn.title = `${this.entries.length === 1 ? "Why it was not answered" : "Show each one and why"}`;
      if (grow && !reduceMotion.matches) {
        this.li.classList.remove("grew");
        void this.li.offsetWidth;
        this.li.classList.add("grew");
      }
    }
  }

  // Turns entries, in order, into list items: pairs answers with what was heard, folds
  // consecutive ignored entries. `item(e)` returns {li, grouped}: grouped means `e` went into
  // the ignored line already on screen.
  class ConvoBuilder {
    constructor() { this.reset(); }
    reset() { this.lastHeard = null; this.group = null; }
    item(e) {
      if (e.kind === "ignored") {
        if (this.group) { this.group.add(e); return { li: this.group.li, grouped: true }; }
        this.group = new IgnoredGroup(e);
        return { li: this.group.li, grouped: false };
      }
      this.group = null;
      if (e.kind === "heard") this.lastHeard = e;
      else if (e.kind === "reply" && !e.proactive) {
        if (this.lastHeard && e.t - this.lastHeard.t < 300) e._heard = this.lastHeard;
        this.lastHeard = null;
      }
      return { li: transcriptItem(e), grouped: false };
    }
  }

  const chat = new ConvoBuilder();

  function nearBottom() {
    const t = $("transcript");
    return t.scrollHeight - t.scrollTop - t.clientHeight < 80;
  }

  // Follows the conversation unless you scrolled up to read (then it stays put until you come back)
  let follow = true;
  function watchScroll() {
    const t = $("transcript");
    let timer;
    const check = () => { clearTimeout(timer); timer = setTimeout(() => { follow = nearBottom(); }, 120); };
    for (const ev of ["wheel", "touchmove", "keydown", "pointerdown"]) t.addEventListener(ev, check, { passive: true });
    // the listening strip opening, a notice, the window: the view shrinks, the last words stay in sight
    if (window.ResizeObserver) new ResizeObserver(() => { if (follow) t.scrollTop = t.scrollHeight; }).observe(t);
  }

  function scrollTranscript(force, smooth = true) {
    const t = $("transcript");
    if (force) follow = true;
    if (follow) {
      requestAnimationFrame(() => t.scrollTo({ top: t.scrollHeight, behavior: smooth && !reduceMotion.matches ? "smooth" : "auto" }));
    }
  }

  function addTranscript(e, { fresh = false } = {}) {
    if (app.transcriptIds.has(e.id)) return;
    app.transcriptIds.add(e.id);
    if (fresh) earconFor(e);
    if (fresh && app.view === "history") conversations.refreshToday();
    const ol = $("transcript");
    if (e.kind === "ignored" && live.you) { dissolve(live.you.li); live.you = null; }
    const { li, grouped } = chat.item(e);
    // one thought said in several breaths: the question that goes on takes the place of its first part
    if (e.kind === "heard") for (const id of e.replaces || []) dissolve(ol.querySelector(`:scope > li[data-id="${id}"]`));
    if (grouped) {
      $("transcript-empty").hidden = true;
      scrollTranscript(false, !!fresh);
      return;
    }
    // a live bubble (you speaking, Marvin thinking or talking) becomes the entry, in place
    const pending = e.kind === "heard" && e.source !== "typed" ? live.you
      : e.kind === "reply" ? live.marvin : null;
    if (pending) {
      settle(pending, li);
    } else {
      if (!fresh) li.classList.add("settled");
      ol.appendChild(li);
    }
    while (ol.children.length > 200) ol.firstElementChild.remove();
    $("transcript-empty").hidden = true;
    scrollTranscript(false, !!fresh);
  }

  // ------------------------------------------------------------------ talk: live animations
  // The assistant's live signals (voice/assistant.py, add_listener) drive the chat while you talk:
  // "level" (the microphone, ~16 Hz), "utterance" (someone talks, stops, is judged), "partial"
  // (the words understood so far) and "say" (a piece of the reply as it goes to the speaker).
  // Your bubble forms while you speak and Marvin's writes itself as he says it; the listening
  // strip and Marvin's small face follow the microphone, or Marvin's voice while he speaks.

  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const WAVE_BARS = 56;
  const live = {
    levels: new Array(WAVE_BARS).fill(0),   // recent loudness, oldest first
    lastPush: 0,
    mic: 0, micAt: 0,                       // last microphone level and when it came
    speech: false,
    lvl: 0,                                 // smoothed level drawn this frame
    you: null,                              // {li, uid}: your bubble being formed
    marvin: null,                           // {li, said, timers, end}: Marvin's bubble being said
    mouth: [],                              // [{t0, seconds, env}] reply pieces scheduled on the speaker
    mode: "off",
    closingUntil: 0,                        // "Stopped listening" shown until then
    raf: 0,
    colors: null,
  };

  function liveItem(side, extra) {
    const li = el("li", `msg ${side} live${extra ? " " + extra : ""}`);
    li.setAttribute("aria-hidden", "true");
    $("transcript").appendChild(li);
    $("transcript-empty").hidden = true;
    return li;
  }

  function waveEl() {
    const w = el("span", "vu");
    for (let i = 0; i < 5; i++) w.appendChild(el("i"));
    return w;
  }

  // the live bubble `l` becomes the entry `li` (same place, same text: no jump)
  function settle(l, li) {
    if (l.timers) l.timers.forEach(clearTimeout);
    li.classList.add("settled", "just-settled");
    l.li.replaceWith(li);
    if (l === live.you) live.you = null;
    if (l === live.marvin) { live.marvin = null; live.mouth = []; }
    setTimeout(() => li.classList.remove("just-settled"), 600);
  }

  function dissolve(li) {
    if (!li || !li.isConnected) return;
    if (reduceMotion.matches) { li.remove(); return; }
    li.style.height = `${li.offsetHeight}px`;
    li.classList.add("dissolving");
    void li.offsetHeight;
    li.style.height = "0px";
    setTimeout(() => li.remove(), 380);
  }

  // Your bubble only appears when Marvin is listening to you (a listening window, or no wake word),
  // or once the words so far start with "Marvin": noises and talk around him move the strip only.
  // It waits 300 ms, so a click or a cough (dropped as too short) never flashes a bubble.
  function listeningToYou() {
    const v = app.voice;
    return !!v && (v.status === "listening" || !v.wake);
  }

  function youBubble(uid) {
    if (live.you && live.you.uid === uid) return live.you;
    if (live.you) dissolve(live.you.li);
    const li = liveItem("you");
    const b = el("p", "bubble");
    b.append(waveEl(), el("span", "words"));
    li.appendChild(b);
    live.you = { li, uid };
    scrollTranscript(true);
    return live.you;
  }

  function onUtterance(u) {
    if (u.state === "start") {
      clearTimeout(live.youTimer);
      live.heardUid = u.uid;
      live.youTimer = setTimeout(() => {
        if (live.heardUid === u.uid && listeningToYou()) youBubble(u.uid);
      }, 300);
    } else if (u.state === "end") {
      if (live.heardUid === u.uid && listeningToYou()) youBubble(u.uid);
      if (live.you && live.you.uid === u.uid) live.you.li.classList.add("understanding");
      labelLive();
    } else if (u.state === "done") {
      clearTimeout(live.youTimer);
      live.heardUid = null;
      const l = live.you;
      if (l && l.uid === u.uid) setTimeout(() => { if (live.you === l) { dissolve(l.li); live.you = null; } }, 250);
      labelLive();
    }
  }

  function onPartial(p) {
    if (!(live.you && live.you.uid === p.uid)) {
      if (live.heardUid !== p.uid || !(listeningToYou() || /^\W*marvin\b/i.test(p.text))) return;
      youBubble(p.uid);
    }
    const w = live.you.li.querySelector(".words");
    if (w.textContent === p.text) return;
    w.textContent = p.text;
    w.classList.remove("fresh-words");
    void w.offsetWidth;
    w.classList.add("fresh-words");
    scrollTranscript(false);
  }

  function marvinBubble() {
    if (live.marvin && live.marvin.said) return live.marvin;
    let li = live.marvin ? live.marvin.li : liveItem("marvin");
    li.classList.remove("thinking");
    li.textContent = "";
    const b = el("p", "bubble");
    const said = el("span", "said");
    b.appendChild(said);
    li.appendChild(b);
    live.marvin = { li, said, timers: [], end: 0 };
    return live.marvin;
  }

  function onSay(s) {
    if (!(live.marvin && live.marvin.said)) earcons.play("reply");
    const m = marvinBubble();
    m.li.classList.add("speaking");
    const now = performance.now() / 1000;
    const t0 = Math.max(now + 0.08, m.end);          // after the pieces already queued
    const seconds = Math.max(0.2, s.seconds || 0.2);
    m.end = t0 + seconds;
    live.mouth.push({ t0, seconds, env: s.envelope || [] });
    const words = (s.text || "").split(/\s+/).filter(Boolean);
    words.forEach((w, i) => {
      const at = (t0 + (seconds * 0.92 * i) / Math.max(1, words.length) - now) * 1000;
      m.timers.push(setTimeout(() => {
        if (m.said.childNodes.length) m.said.append(" ");
        m.said.appendChild(el("span", "w", w));
        scrollTranscript(false, false);
      }, Math.max(0, at)));
    });
    ensureLoop();
  }

  function thinkingBubble(on) {
    if (on && !live.marvin) {
      const li = liveItem("marvin", "thinking");
      const b = el("p", "bubble");
      const dots = el("span", "dots");
      for (let i = 0; i < 3; i++) dots.appendChild(el("i"));
      b.appendChild(dots);
      li.appendChild(b);
      live.marvin = { li, said: null, timers: [], end: 0 };
      scrollTranscript(false);
    } else if (!on && live.marvin && !live.marvin.said) {
      dissolve(live.marvin.li);
      live.marvin = null;
    }
  }

  function mouthLevel(now) {
    for (const m of live.mouth) {
      if (now >= m.t0 && now < m.t0 + m.seconds) return m.env[Math.floor((now - m.t0) * 20)] || 0;
    }
    return 0;
  }

  function liveMode(v) {
    if (!v || v.state !== "on") return v && v.state === "starting" ? "starting" : "off";
    if (v.muted && v.status !== "speaking" && v.status !== "thinking") return "muted";
    return v.status;                                  // idle, listening, thinking, speaking
  }

  const LISTEN_LABELS = {
    starting: "Waking up…", muted: "Microphone muted", listening: "Ask your question…",
    thinking: "Thinking…", speaking: "Marvin is speaking",
  };

  function setFading(node, text) {
    if (node.textContent === text) return;
    node.textContent = text;
    node.classList.remove("fade-in");
    void node.offsetWidth;
    node.classList.add("fade-in");
  }

  function updateLive(v) {
    const mode = liveMode(v);
    if (mode === "listening" && v.listen_s != null && !v.hearing) {
      live.frozenLeft = null;
      const now = performance.now() / 1000;
      if (live.mode !== "listening" || !live.windowEnd) live.windowTotal = Math.max(1, v.listen_s);
      live.windowEnd = now + v.listen_s;
    } else if (mode !== "listening") {
      live.windowEnd = 0;
    }
    const was = live.mode;
    live.mode = mode;
    const strip = $("listen");
    if (was !== "listening" && mode === "listening") {
      // a window someone asked for (Talk now, "Marvin." alone), or the follow-up after an answer
      live.windowKind = was === "speaking" || was === "thinking" ? "follow-up" : "asked";
      // The assistant chimes on the computer's speaker when a window opens (config.chime): the
      // browser only adds its own note when this page pressed Talk now (the person holding it
      // hears at once that it worked), or when the assistant's chime is off.
      if (live.windowKind === "asked" && (performance.now() - app.talkNowAt < 3000 || v.chime === false)) earcons.play("open");
    }
    if (was === "listening" && mode === "idle") {
      live.closingUntil = performance.now() + 1600;
      // closed without a question; not after the follow-up window every answer opens
      if (live.windowKind === "asked" && performance.now() - earcons.lastMiss > 800) earcons.play("close");
    }
    if (mode !== "idle") live.closingUntil = 0;
    strip.dataset.mode = mode;
    labelLive();
    if (mode === "thinking") thinkingBubble(true);
    else if (mode !== "speaking") thinkingBubble(false);   // "speaking" comes just before the first words
    if (mode !== "speaking" && live.marvin && live.marvin.said) live.marvin.li.classList.remove("speaking");
    if (mode === "off" || mode === "muted") {
      if (live.you) { dissolve(live.you.li); live.you = null; }
    }
    ensureLoop();
  }

  /** Someone talks in the listening window, or their words are being understood: it does not run out. */
  function hearingNow() {
    return !!live.speech || live.heardUid != null || !!(app.voice && app.voice.hearing);
  }

  function labelLive() {
    const strip = $("listen");
    let text = LISTEN_LABELS[live.mode] || "";
    if (live.mode === "idle") {
      if (live.closingUntil > performance.now()) text = "Stopped listening";
      else text = app.voice && app.voice.wake ? "Say “Marvin, …”" : "Waiting for you to speak";
    }
    if (live.speech && (live.mode === "idle" || live.mode === "listening")) text = live.mode === "listening" ? "Listening…" : "Hearing something…";
    if (live.heardUid != null && !live.speech && live.mode !== "thinking" && live.mode !== "speaking") text = "Understanding…";
    strip.classList.toggle("closing", live.closingUntil > performance.now());
    // while you talk (or your words are being understood) the listening window waits: no draining bar
    strip.classList.toggle("hearing", !!live.speech || (live.mode === "listening" && hearingNow()));
    setFading($("listen-label"), text);
  }

  function onLevel(l) {
    live.mic = l.mic || 0;
    live.micAt = performance.now();
    if (live.speech !== !!l.speech) { live.speech = !!l.speech; labelLive(); }
    ensureLoop();
  }

  function waveColors() {
    const cs = getComputedStyle(document.documentElement);
    return { accent: cs.getPropertyValue("--accent").trim(), text: cs.getPropertyValue("--text").trim(),
             muted: cs.getPropertyValue("--muted").trim() };
  }

  function ensureLoop() {
    if (live.raf || document.hidden) return;
    live.raf = requestAnimationFrame(frame);
  }

  function frame(ts) {
    live.raf = 0;
    const now = ts / 1000;
    const speaking = live.mode === "speaking";
    let target = speaking ? mouthLevel(now) : live.mic;
    if (!speaking && performance.now() - live.micAt > 250) target = 0;   // no news: quiet
    if (live.mode === "off" || live.mode === "muted" || live.mode === "starting") target = 0;
    live.lvl += (target - live.lvl) * (target > live.lvl ? 0.55 : 0.18);
    if (ts - live.lastPush > 55) {                   // the wave scrolls ~18 bars a second
      live.levels.push(live.lvl);
      live.levels.shift();
      live.lastPush = ts;
    }
    const lv = live.lvl.toFixed(3);
    if (live.windowEnd) {
      if (hearingNow()) {             // it waits while you talk, frozen at what was left
        if (live.frozenLeft == null) live.frozenLeft = Math.max(0, live.windowEnd - now);
        live.windowEnd = now + live.frozenLeft;
      } else {
        live.frozenLeft = null;
      }
      const remain = Math.max(0, Math.min(1, (live.windowEnd - now) / live.windowTotal));
      $("listen").style.setProperty("--remain", remain.toFixed(3));
    }
    if (live.you) live.you.li.style.setProperty("--lvl", live.you.li.classList.contains("understanding") ? 0 : lv);
    if (live.marvin && live.marvin.said) live.marvin.li.style.setProperty("--lvl", lv);
    drawListenWave(ts);
    if (live.closingUntil && live.closingUntil <= performance.now()) { live.closingUntil = 0; labelLive(); }
    const moving = live.lvl > 0.004 || live.levels.some((x) => x > 0.004) || live.closingUntil;
    if (moving || ["idle", "listening", "thinking", "speaking", "starting"].includes(live.mode)) ensureLoop();
  }

  function drawListenWave(ts) {
    const c = $("listen-wave");
    const w = c.clientWidth, h = c.clientHeight;
    if (!w || !h) return;
    const dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
    }
    if (!live.colors) live.colors = waveColors();
    const g = c.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    const mode = live.mode;
    const color = mode === "speaking" ? live.colors.text : mode === "listening" || live.speech ? live.colors.accent : live.colors.muted;
    const step = w / WAVE_BARS;
    const bw = Math.max(2, Math.min(3, step * 0.45));
    const shift = reduceMotion.matches ? 0 : Math.min(1, (ts - live.lastPush) / 55) * step;
    g.fillStyle = color;
    for (let i = 0; i < WAVE_BARS; i++) {
      let v = live.levels[i];
      if (mode === "thinking") v = 0.08 + 0.08 * Math.sin(ts / 260 - i * 0.35);   // a slow travelling ripple
      const age = i / (WAVE_BARS - 1);                // older bars fade out on the left
      const bh = Math.max(bw, v * (h - 4));
      const x = i * step - shift + (step - bw) / 2;
      g.globalAlpha = (mode === "idle" && !live.speech ? 0.45 : 1) * (0.15 + 0.85 * age);
      roundRect(g, x, (h - bh) / 2, bw, bh, bw / 2);
    }
    g.globalAlpha = 1;
  }

  function roundRect(g, x, y, w, h, r) {
    g.beginPath();
    g.moveTo(x + r, y);
    g.arcTo(x + w, y, x + w, y + h, r);
    g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r);
    g.arcTo(x, y, x + w, y, r);
    g.fill();
  }

  function renderVoice(v) {
    app.voice = v;
    const on = v.state === "on";
    const status = on ? v.status : v.state === "unavailable" ? "off" : v.state;
    miniFace.setStatus(status, !!v.muted);
    let text;
    if (v.state === "unavailable") text = "Voice needs marvin-host run";
    else if (v.state === "off") text = "Voice off";
    else if (v.state === "starting") text = "Starting, loading the models…";
    else if (v.state === "stopping") text = "Stopping…";
    else if (v.state === "error") text = "Voice unavailable";
    else if (v.muted && v.status === "idle") text = "Microphone muted";
    else text = {
      idle: v.wake ? "Say “Marvin, …”" : "Waiting for you to speak",
      listening: "Listening: ask your question", thinking: "Thinking…", speaking: "Speaking…",
    }[v.status] || v.status;
    setFading($("voice-status"), text);

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
    const listening = on && v.status === "listening";
    $("listen-now").setAttribute("aria-pressed", String(listening));
    $("listen-now-label").textContent = listening ? "Listening" : "Talk now";
    $("listen-now").title = listening ? "Marvin is listening: ask your question. Press to stop listening"
      : "Ask a question without saying “Marvin”";
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
    updateLive(v);
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
    watchScroll();
    $("voice-switch").addEventListener("change", (ev) => voiceCommand(ev.target.checked ? "/api/voice/on" : "/api/voice/off"));
    $("voice-retry").addEventListener("click", () => voiceCommand("/api/voice/on"));
    $("listen-now").addEventListener("click", () => {
      const v = app.voice;
      if (!v || v.state !== "on") return;
      const stop = v.status === "listening";
      // answer at once; the server's reply confirms (or corrects) it a moment later
      if (!stop) app.talkNowAt = performance.now();
      renderVoice({ ...v, status: stop ? "idle" : "listening", muted: false, listen_s: stop ? null : 6 });
      voiceCommand("/api/voice/listen", { on: !stop });
    });
    $("stop-speaking").addEventListener("click", () => voiceCommand("/api/voice/stop-speaking"));
    $("mute").addEventListener("click", () => voiceCommand("/api/voice/mute", { muted: !(app.voice && app.voice.muted) }));
    $("ask-form").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const input = $("ask-input");
      const text = input.value.trim();
      if (!text) return;
      input.value = "";
      scrollTranscript(true);
      await voiceCommand("/api/voice/ask", { text });
      input.focus();
    });
  }

  async function loadVoice() {
    const r = await api("/api/voice");
    app.voiceSettings = r.settings;
    for (const e of r.transcript) addTranscript(e);
    renderVoice(r.voice);
    scrollTranscript(true, false);
  }

  // ------------------------------------------------------------------ talk: Marvin's small face
  // A client-side version of the robot's eyes (face.py: two rounded rectangles, lids drawn in the
  // background colour, the same colours and geometry), drawn at the screen's refresh rate and
  // driven by the voice: sleepy while the voice is off, calm and blinking while it waits,
  // attentive and following your voice while it listens, glancing up and aside while it thinks,
  // moving with its own voice while it speaks.

  const miniFace = (() => {
    const BG = [20, 18, 17], WHITE = [236, 230, 218], AMBER = [255, 190, 120];
    const KEYS = ["w", "h", "r", "sp", "dy", "open", "lt", "tilt", "lb", "bri", "warm"];
    const P = (o) => ({ w: 64, h: 82, r: 22, sp: 104, dy: 0, open: 1, lt: 0, tilt: 0, lb: 0, bri: 1, warm: 0.1, ...o });
    const EXPR = {                                   // the same numbers as face.py's EXPRESSIONS
      calm: P({ w: 68, h: 72, r: 22, dy: 2, warm: 0.15 }),
      attentive: P({ w: 68, h: 90, r: 26, sp: 108, dy: -3 }),
      thinking: P({ w: 64, h: 74, r: 20, lt: 0.14, tilt: 0.12, warm: 0.12 }),
      speaking: P({ w: 68, h: 80, r: 24, sp: 106, warm: 0.15 }),
      sleepy: P({ w: 66, h: 74, r: 22, dy: 6, lt: 0.58, bri: 0.85, warm: 0.45 }),
      muted: P({ w: 66, h: 70, r: 22, dy: 4, lt: 0.34, bri: 0.8, warm: 0.3 }),
      waking: P({ w: 66, h: 84, r: 24, sp: 106 }),
      concerned: P({ w: 64, h: 80, r: 16, lt: 0.4, tilt: 0.4, warm: 0.2 }),
    };
    const BLINK = [0.07, 0.04, 0.13], SLOW = [0.35, 0.3, 0.55];
    const vec = (p) => KEYS.map((k) => p[k]);
    const obj = (a) => Object.fromEntries(KEYS.map((k, i) => [k, a[i]]));
    const box = $("mini-face"), cv = $("mini-face-canvas");
    let status = "off", muted = false, target = EXPR.sleepy;
    let p = vec(EXPR.sleepy), pv = p.map(() => 0);
    let gaze = [0, 4], gv = [0, 0];
    let last = 0, raf = 0, idleTimer = 0;
    let blinks = [], nextBlink = 0, glance = [0, 0], nextGlance = 0, thinkSide = 1, nextThink = 0;

    // exact step of a critically damped spring (face.py spring_step)
    function spring(x, v, tgt, w, dt) {
      const e = Math.exp(-w * dt);
      for (let i = 0; i < x.length; i++) {
        const x0 = x[i] - tgt[i], c = v[i] + w * x0;
        x[i] = tgt[i] + (x0 + c * dt) * e;
        v[i] = (v[i] - w * c * dt) * e;
      }
    }
    const smooth = (u) => { u = Math.min(1, Math.max(0, u)); return u * u * (3 - 2 * u); };
    function closure(t, s0, [c, h, o]) {
      const u = t - s0;
      if (u < 0 || u > c + h + o) return 0;
      if (u < c) return smooth(u / c);
      if (u < c + h) return 1;
      return 1 - smooth((u - c - h) / o);
    }

    function expression() {
      if (status === "error") return "concerned";
      if (status === "starting") return "waking";
      if (status === "listening") return "attentive";
      if (status === "thinking") return "thinking";
      if (status === "speaking") return "speaking";
      if (status === "idle") return muted ? "muted" : "calm";
      return "sleepy";                                // off, stopping, unavailable
    }

    function setStatus(st, m) {
      if (st === status && m === muted) return;
      status = st; muted = m;
      box.dataset.status = st;
      target = EXPR[expression()];
      if (reduceMotion.matches) { p = vec(target); gaze = [0, st === "thinking" ? -10 : 0]; }
      wake();
    }

    function color(q) {
      const w = Math.min(1, Math.max(0, q.warm)), b = Math.min(1, Math.max(0, q.bri));
      return `rgb(${BG.map((bg, i) => Math.round(bg + b * ((1 - w) * WHITE[i] + w * AMBER[i] - bg))).join(",")})`;
    }

    function rrect(g, cx, cy, w, h, r) {
      r = Math.max(0, Math.min(r, w / 2, h / 2));
      const x = cx - w / 2, y = cy - h / 2;
      g.beginPath();
      g.moveTo(x + r, y);
      g.arcTo(x + w, y, x + w, y + h, r);
      g.arcTo(x + w, y + h, x, y + h, r);
      g.arcTo(x, y + h, x, y, r);
      g.arcTo(x, y, x + w, y, r);
      g.closePath();
      g.fill();
    }

    // face.py render(), for one frame, in screen pixels (240 x 280) scaled into the canvas
    function draw(q, gx, gy, blink) {
      const w = cv.clientWidth, h = cv.clientHeight;
      if (!w || !h) return;
      const dpr = Math.min(3, window.devicePixelRatio || 1);
      if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
      const g = cv.getContext("2d");
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.fillStyle = `rgb(${BG.join(",")})`;
      g.fillRect(0, 0, cv.width, cv.height);
      const sc = (w / 196) * dpr;                     // the eyes' area of the screen fills the square
      g.setTransform(sc, 0, 0, sc, (w * dpr) / 2 - 120 * sc, (h * dpr) / 2 - 146 * sc);
      const lean = Math.max(-1, Math.min(1, gx / 28));
      const openness = Math.max(0, Math.min(1, q.open * (1 - blink)));
      const eye = color(q), bg = `rgb(${BG.join(",")})`;
      for (const side of [-1, 1]) {
        const k = 1 + side * 0.06 * lean;
        const ew = q.w * k, eh = q.h * k;
        const cx = 120 + (side * q.sp) / 2 + gx;
        const he = Math.max(5, eh * openness);
        const cy = 146 + q.dy + gy + (eh - he) * 0.35;
        const top = cy - he / 2, bottom = cy + he / 2;
        g.fillStyle = eye;
        rrect(g, cx, cy, ew, he, q.r * k);
        g.fillStyle = bg;
        if (q.lt > 1e-3 && he > 5) {
          const ymid = top + q.lt * he, slope = (q.tilt * he) / 2;
          const xin = cx - side * (ew / 2 + 3), xout = cx + side * (ew / 2 + 3), ycap = top - 3;
          g.beginPath();
          g.moveTo(xin, ycap); g.lineTo(xout, ycap);
          g.lineTo(xout, Math.max(ymid + slope, ycap + 1)); g.lineTo(xin, Math.max(ymid - slope, ycap + 1));
          g.closePath(); g.fill();
        }
        if (q.lb > 1e-3 && he > 5) {
          const rx = ew * 0.56, ry = he * 0.5;
          g.beginPath(); g.ellipse(cx, bottom - q.lb * he + ry, rx, ry, 0, 0, 2 * Math.PI); g.fill();
        }
      }
    }

    function behaviour(t, lvl) {
      const q = obj(p);
      let gt = [0, 0], w = 16;
      if (status === "listening") {                   // looking at you, a little wider with your voice
        q.w *= 1 + 0.07 * lvl; q.h *= 1 + 0.1 * lvl;
        if (t > nextGlance) { glance = [(Math.random() - 0.5) * 4, (Math.random() - 0.5) * 2.5]; nextGlance = t + 0.8 + Math.random() * 1.6; }
        gt = [glance[0], -3 + glance[1]];
      } else if (status === "thinking") {             // glancing up and aside
        if (t > nextThink) {
          thinkSide = -thinkSide; nextThink = t + 1.1 + Math.random() * 1.1;
          if (Math.random() < 0.35) blinks.push([t + 0.2, SLOW]);
        }
        gt = [thinkSide * 15, -13]; w = 11;
      } else if (status === "speaking") {             // moving with the voice
        q.h *= 1 - 0.16 * lvl; q.w *= 1 + 0.05 * lvl; q.dy -= 4 * lvl;
        gt = [0, -1];
      } else if (status === "idle") {                 // small glances now and then
        if (t > nextGlance) {
          glance = Math.random() < 0.3 ? [(Math.random() - 0.5) * 30, (Math.random() - 0.6) * 10] : [(Math.random() - 0.5) * 5, (Math.random() - 0.5) * 3];
          nextGlance = t + 1.2 + Math.random() * 2.5;
        }
        gt = glance;
      } else {
        gt = [0, 5]; w = 4;
      }
      spring(gaze, gv, gt, w, Math.min(0.05, t - last || 0));
      const sleepy = expression() === "sleepy" || expression() === "muted";
      if (!nextBlink) nextBlink = t + 2 + Math.random() * 4;
      if (t >= nextBlink) {
        blinks.push([t, sleepy ? SLOW : BLINK]);
        if (!sleepy && Math.random() < 0.15) blinks.push([t + 0.32, BLINK]);
        nextBlink = t + (sleepy ? 5 + Math.random() * 5 : 2 + Math.random() * 4);
      }
      blinks = blinks.filter(([s0, tm]) => t <= s0 + tm[0] + tm[1] + tm[2]);
      const blink = Math.max(0, ...blinks.map(([s0, tm]) => closure(t, s0, tm)));
      return [q, blink];
    }

    function visible() { return !document.hidden && cv.getClientRects().length > 0; }

    function tick(ts) {
      raf = 0;
      if (!visible()) { idleTimer = setTimeout(wake, 500); return; }
      const t = ts / 1000;
      const dt = last ? Math.min(0.05, t - last) : 0;
      if (reduceMotion.matches) {                    // still: the expression only, no blinks
        draw(obj(p), gaze[0], gaze[1], 0);
        last = 0;
        return;
      }
      const omega = expression() === "sleepy" ? 2.2 : status === "speaking" ? 12 : 8;
      spring(p, pv, vec(target), omega, dt);
      const lvl = status === "listening" || status === "speaking" ? live.lvl : 0;
      const [q, blink] = behaviour(t, lvl);
      last = t;
      box.style.setProperty("--lvl", lvl.toFixed(3));
      draw(q, gaze[0], gaze[1], blink);
      // asleep and settled: a few frames a second are enough
      const settled = pv.every((x) => Math.abs(x) < 0.01) && Math.abs(gv[0]) + Math.abs(gv[1]) < 0.01 && !blinks.length;
      if (settled && expression() === "sleepy") idleTimer = setTimeout(wake, 120);
      else raf = requestAnimationFrame(tick);
    }

    function wake() {
      clearTimeout(idleTimer);
      if (!raf) raf = requestAnimationFrame(tick);
    }

    box.dataset.status = status;
    document.addEventListener("visibilitychange", wake);
    reduceMotion.addEventListener("change", wake);
    return { setStatus, wake };
  })();

  // ------------------------------------------------------------------ talk: sounds (earcons)
  // Short synthesised notes played by this browser (WebAudio, no files), quiet and soft:
  // listening opens (two rising notes), listening closes without a question (two falling notes),
  // not understood while listening (a low blip), an answer starts (a faint tick). On by default,
  // off in Settings (ui_sounds). Browsers only allow sound after a gesture: the audio context is
  // created at the first click or key press on the page, and nothing plays before.

  const earcons = {
    ctx: null,
    lastMiss: 0,
    unlock() {
      if (!this.ctx) {
        const AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return;
        try { this.ctx = new AC(); } catch (e) { return; }
      }
      if (this.ctx.state === "suspended") this.ctx.resume().catch(() => {});
    },
    tone(f0, f1, at, dur, gain, type = "sine") {
      const c = this.ctx, o = c.createOscillator(), g = c.createGain();
      o.type = type;
      o.frequency.setValueAtTime(f0, at);
      if (f1 !== f0) o.frequency.exponentialRampToValueAtTime(f1, at + dur);
      g.gain.setValueAtTime(0.0001, at);
      g.gain.linearRampToValueAtTime(gain, at + 0.012);
      g.gain.exponentialRampToValueAtTime(0.0001, at + dur);
      o.connect(g).connect(c.destination);
      o.start(at);
      o.stop(at + dur + 0.02);
    },
    play(name) {
      if (app.settings.ui_sounds === false || !this.ctx || this.ctx.state !== "running") return;
      const t = this.ctx.currentTime + 0.01;
      if (name === "open") { this.tone(587.3, 587.3, t, 0.14, 0.05); this.tone(880, 880, t + 0.09, 0.2, 0.045); }
      else if (name === "close") { this.tone(783.99, 783.99, t, 0.14, 0.035); this.tone(523.25, 523.25, t + 0.1, 0.24, 0.03); }
      else if (name === "miss") { this.lastMiss = performance.now(); this.tone(196, 164.8, t, 0.13, 0.06, "triangle"); }
      else if (name === "reply") this.tone(1318.5, 1318.5, t, 0.07, 0.014);
    },
  };

  function earconFor(e) {
    // not understood while Marvin was listening to you: a tiny low blip
    if (e.kind === "ignored" && live.mode === "listening") earcons.play("miss");
  }

  function setupEarcons() {
    const unlock = () => {
      earcons.unlock();
      if (earcons.ctx) for (const ev of ["pointerdown", "keydown"]) document.removeEventListener(ev, unlock, true);
    };
    for (const ev of ["pointerdown", "keydown"]) document.addEventListener(ev, unlock, true);
  }

  // ------------------------------------------------------------------ talk: what Marvin knows now

  function nowPart(text, cls, icon, priority = 0) {
    const sp = el("span", `now-part${cls ? " " + cls : ""}`);
    sp.dataset.p = priority;                       // on a narrow line, the highest go first
    if (icon) sp.insertAdjacentHTML("afterbegin", `<svg class="icon xs" aria-hidden="true"><use href="#${icon}"/></svg>`);
    sp.append(text);
    return sp;
  }

  function renderNow(s) {
    const line = $("now-line"), box = $("now-items");
    const parts = [];
    const said = [];
    if (!s.online) { parts.push(nowPart("Robot not connected", "lead")); said.push("the robot is not connected"); }
    else if (!s.present) { parts.push(nowPart("Nobody in front of Marvin", "lead")); said.push("nobody in front of Marvin"); }
    else {
      const lead = s.seated ? (s.seated_s < 60 ? "Just sat down" : `Seated ${fmtDuration(s.seated_s)}`) : "You're here";
      parts.push(nowPart(lead, "lead"));
      said.push(s.seated ? `you have been seated for ${fmtDuration(s.seated_s)}` : "you are here");
      if (s.distance_m != null) { parts.push(nowPart(`${s.distance_m.toFixed(1)} m`, "", null, 4)); said.push(`${s.distance_m.toFixed(1)} metres away`); }
      if (s.breath_rate != null) { parts.push(nowPart(`${Math.round(s.breath_rate)}/min`, "", "i-breath", 3)); said.push(`breathing ${Math.round(s.breath_rate)} per minute`); }
      if (s.heart_rate != null) { parts.push(nowPart(`${Math.round(s.heart_rate)}/min`, "", "i-heart", 2)); said.push(`heart ${Math.round(s.heart_rate)} per minute`); }
    }
    if (s.simulated) {
      const sim = nowPart("simulated", "sim", null, 1);
      sim.prepend(el("i", "sim-mark"));
      parts.push(sim);
      said.push("simulated sensors");
    }
    const sig = parts.map((x) => x.className + x.textContent).join("|");
    if (sig !== line.dataset.sig) {
      line.dataset.sig = sig;
      box.replaceChildren(...parts);
      fitNow();
    }
    line.hidden = false;
    line.setAttribute("aria-label", `Marvin knows: ${said.join(", ")}. Open the Robot panel`);
  }

  // hides the least useful parts (distance, then breathing, heart, the simulated mark) until the
  // line fits: it stays one line on a phone
  function fitNow() {
    const box = $("now-items");
    const parts = [...box.children];
    parts.forEach((x) => { x.hidden = false; });
    if (!box.clientWidth) return;
    const order = parts.filter((x) => +x.dataset.p > 0).sort((a, b) => b.dataset.p - a.dataset.p);
    for (const x of order) {
      if (box.scrollWidth <= box.clientWidth + 1) break;
      x.hidden = true;
    }
  }

  // ------------------------------------------------------------------ simulated sensors badge

  function setupSimBadge() {
    const b = $("sim-badge");
    const set = (open) => { b.classList.toggle("open", open); b.setAttribute("aria-expanded", String(open)); };
    b.addEventListener("click", (ev) => { ev.stopPropagation(); set(!b.classList.contains("open")); });
    b.addEventListener("blur", () => set(false));
    b.addEventListener("keydown", (ev) => { if (ev.key === "Escape") set(false); });
    document.addEventListener("click", () => set(false));
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
    let opened = false;
    es.addEventListener("open", () => {
      status("live", "Live");
      // after a reconnection (marvin-host restarted, the computer slept), catch up on what was missed
      if (opened) loadVoice().catch(() => {});
      opened = true;
    });
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
    es.addEventListener("level", (m) => onLevel(JSON.parse(m.data)));
    es.addEventListener("utterance", (m) => onUtterance(JSON.parse(m.data)));
    es.addEventListener("partial", (m) => onPartial(JSON.parse(m.data)));
    es.addEventListener("say", (m) => onSay(JSON.parse(m.data)));
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
    $("v-tools").checked = s.tools !== false;
    $("v-internet").checked = s.internet !== false;
    $("v-home").value = s.home_place || "";
    renderToolList();
  }

  // the tools Marvin has, and whether the switches above leave each one on
  function renderToolList() {
    const list = $("v-tool-list");
    const tools = (voiceOptions && voiceOptions.tools) || [];
    const on = $("v-tools").checked, internet = $("v-internet").checked;
    $("v-internet").disabled = !on;
    list.hidden = !tools.length;
    list.replaceChildren(...tools.map((t) => {
      const active = on && (internet || !t.online);
      const li = el("li", active ? "on" : "off");
      const state = !on ? "Off" : active ? "On" : "Off: needs the internet";
      li.append(el("code", null, t.name), el("span", `state${active ? " on" : ""}`, state),
        el("span", "desc", t.description + (t.online ? " Online." : " Works offline.")));
      return li;
    }));
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
      $("f-sounds").checked = s.ui_sounds !== false;
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
    // sounds apply at once (and play a note, so you hear what they are like)
    $("f-sounds").addEventListener("change", async (ev) => {
      const on = ev.target.checked;
      try {
        const r = await post("/api/settings", { ui_sounds: on });
        applySettings(r.settings);
        if (on) { earcons.unlock(); setTimeout(() => earcons.play("open"), 60); }
        flash("settings-saved", on ? "Sounds on" : "Sounds off");
      } catch (e) {
        ev.target.checked = !on;
      }
    });
    $("v-tts").addEventListener("change", fillVoices);
    $("v-tools").addEventListener("change", renderToolList);
    $("v-internet").addEventListener("change", renderToolList);
    $("settings-form").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const err = $("settings-error");
      err.hidden = true;
      const form = ev.target;
      const body = {
        break_interval_min: Number($("f-break").value),
        quiet_hours: { enabled: $("f-quiet").checked, start: $("f-quiet-start").value || "22:00", end: $("f-quiet-end").value || "07:00" },
        clock: form.querySelector('input[name="clock"]:checked').value,
        ui_sounds: $("f-sounds").checked,
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
        tools: $("v-tools").checked,
        internet: $("v-internet").checked,
        home_place: $("v-home").value.trim(),
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
    setupEarcons();
    setupSimBadge();
    conversations.setup();
    $("now-line").addEventListener("click", () => { location.hash = "#robot"; });
    for (const r of document.querySelectorAll('input[name="log-filter"]')) r.addEventListener("change", renderLog);
    $("day-prev").addEventListener("click", () => shiftDay(-1));
    $("day-next").addEventListener("click", () => shiftDay(1));
    document.addEventListener("visibilitychange", () => { robotStream.update(); ensureLoop(); });
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
        fitNow();
        if (app.view === "robot") drawScene();
      }, 150);
    });
  }

  init();
})();

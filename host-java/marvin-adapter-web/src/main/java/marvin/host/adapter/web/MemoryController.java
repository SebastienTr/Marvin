// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import marvin.host.application.memory.port.in.BrowseMemory;
import marvin.host.application.memory.port.in.ConfigureMemory;
import marvin.host.application.memory.port.in.ConfirmForgetting;
import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.in.ExportMemory;
import marvin.host.application.memory.port.in.ForgetMemory;
import marvin.host.application.memory.port.in.ManageFacts;
import marvin.host.application.memory.port.in.MemoryHealth;
import marvin.host.application.memory.port.in.VisualiseMemory;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactTimeline;
import marvin.host.domain.memory.MemoryGraph;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySettings;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.memory.MemoryText;
import marvin.host.domain.memory.ProfileText;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * The memory API for the app (docs/design.md 5.6; docs/memory.md lists every route): facts (list, filter, detail
 * with sources, remember, edit, pin, archive, review, forget with a confirmation), forgetting everything (a code and a
 * typed phrase), the profile (current, versions with diffs, edit, restore), episodes, the raw log, export, the
 * worker, the memory settings (the per-source switches among them), and four read-only pictures of it (graph,
 * meaning map, timeline, flow). Behind the same access rules as the rest of the
 * API: every {@code POST} path is listed in {@link AccessFilter#POST_PATHS} (JSON only, same origin). Times are Unix
 * seconds, as elsewhere in the API.
 */
@RestController
public class MemoryController {
    static final int PAGE = 50;

    private final ConsolidateMemory worker;
    private final MemoryHealth health;
    private final ManageFacts facts;
    private final BrowseMemory browse;
    private final ConfirmForgetting forgetting;
    private final ExportMemory export;
    private final ConfigureMemory settings;
    private final VisualiseMemory views;
    private final Clocks clocks;
    private final ZoneId zone;

    public MemoryController(ConsolidateMemory worker, MemoryHealth health, ManageFacts facts, BrowseMemory browse,
                            ConfirmForgetting forgetting, ExportMemory export, ConfigureMemory settings,
                            VisualiseMemory views, Clocks clocks, LocalDays days) {
        this.worker = worker;
        this.health = health;
        this.facts = facts;
        this.browse = browse;
        this.forgetting = forgetting;
        this.export = export;
        this.settings = settings;
        this.views = views;
        this.clocks = clocks;
        this.zone = days.zone();
    }

    /** Not found: 404 with the reason. */
    static final class NotFound extends RuntimeException {
        NotFound(String message) {
            super(message);
        }
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<byte[]> notFound(NotFound e) {
        return Responses.error(404, e.getMessage());
    }

    /** The use cases say what the owner got wrong with an {@link IllegalArgumentException}. */
    @ExceptionHandler({IllegalArgumentException.class, ApiController.BadRequest.class})
    public ResponseEntity<byte[]> badRequest(RuntimeException e) {
        return Responses.error(e.getMessage() != null && e.getMessage().startsWith("no such ") ? 404 : 400, e.getMessage());
    }

    private Instant now() {
        long micros = Math.round(clocks.wallSeconds() * 1e6);
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
    }

    // ------------------------------------------------------------------ overview

    /** What the Memory screen's header shows: counts per filter, the profile's version, the worker, the settings. */
    @GetMapping("/api/memory")
    public ResponseEntity<byte[]> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", counts("", ""));
        m.put("profile", browse.profile().map(this::version).orElse(null));
        m.put("worker", workerPayload());
        m.put("settings", settings.settings().toMap());
        m.put("pending_forget", forgetting.pending().size());
        return Responses.json(m);
    }

    // ------------------------------------------------------------------ facts

    /**
     * {@code ?filter=all|pinned|suggested|archived|past&q=&subject=&kind=&sensitivity=&limit=&offset=}. {@code all}:
     * current, not archived; {@code suggested}: extracted and not reviewed yet; {@code past}: no longer true.
     */
    @GetMapping("/api/memory/facts")
    public ResponseEntity<byte[]> facts(HttpServletRequest rq) {
        String filter = param(rq, "filter", "all");
        String q = param(rq, "q", "");
        String subject = param(rq, "subject", "");
        int limit = (int) ApiController.queryInt(rq, "limit", PAGE, 1, 500);
        int offset = (int) ApiController.queryInt(rq, "offset", 0, 0, 1_000_000);
        FactStore.Query query = query(filter, q, subject, param(rq, "kind", ""), param(rq, "sensitivity", ""), limit, offset);
        ManageFacts.Page page = facts.list(query);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filter", filter);
        m.put("facts", page.facts().stream().map(this::fact).toList());
        m.put("total", page.total());
        m.put("counts", counts(q, subject));
        return Responses.json(m);
    }

    private FactStore.Query query(String filter, String q, String subject, String kind, String sensitivity, int limit, int offset) {
        Sensitivity s = sensitivity.isEmpty() ? null : Sensitivity.parse(sensitivity, null);
        if (!sensitivity.isEmpty() && (s == null || s == Sensitivity.SECRET)) {
            throw new ApiController.BadRequest("sensitivity must be normal, personal or sensitive");
        }
        Instant now = now();
        return switch (filter) {
            case "all" -> new FactStore.Query(q, subject, kind, "current", s, false, null, null, now, limit, offset);
            case "pinned" -> new FactStore.Query(q, subject, kind, "current", s, null, true, null, now, limit, offset);
            case "suggested" -> new FactStore.Query(q, subject, kind, "current", s, false, null, false, now, limit, offset);
            case "archived" -> new FactStore.Query(q, subject, kind, "current", s, true, null, null, now, limit, offset);
            case "past" -> new FactStore.Query(q, subject, kind, "past", s, null, null, null, now, limit, offset);
            default -> throw new ApiController.BadRequest("filter must be all, pinned, suggested, archived or past");
        };
    }

    private Map<String, Object> counts(String q, String subject) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : List.of("all", "pinned", "suggested", "archived", "past")) {
            m.put(f, facts.list(query(f, q, subject, "", "", 1, 0)).total());
        }
        return m;
    }

    @GetMapping("/api/memory/facts/{id}")
    public ResponseEntity<byte[]> fact(@PathVariable("id") String id) {
        ManageFacts.Detail d = facts.get(uuid(id)).orElseThrow(() -> new NotFound("no such fact"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fact", fact(d.fact()));
        m.put("sources", d.sources().stream().map(this::source).toList());
        m.put("versions", d.versions().stream().map(this::fact).toList());
        return Responses.json(m);
    }

    /** {@code {"statement": "...", "subject"?: "owner", "sensitivity"?: "normal"}}: the owner's fact, written at once. */
    @PostMapping("/api/memory/facts/remember")
    public ResponseEntity<byte[]> remember(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        String statement = string(b, "statement", true);
        String subject = b.get("subject") instanceof String s && !s.isBlank() ? s : "owner";
        Fact f = facts.remember(statement, subject, sensitivity(b));
        return Responses.json(201, Map.of("fact", fact(f)));
    }

    /** {@code {"id", "statement"?, "subject"?, "kind"?, "importance"?, "sensitivity"?}}: a new version by the owner. */
    @PostMapping("/api/memory/facts/edit")
    public ResponseEntity<byte[]> edit(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        Integer importance = null;
        if (b.get("importance") != null) {
            if (!(b.get("importance") instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                    || n.intValue() < 1 || n.intValue() > 10) {
                throw new ApiController.BadRequest("importance must be a whole number from 1 to 10");
            }
            importance = n.intValue();
        }
        Fact f = facts.edit(id(b), new ManageFacts.Edit(string(b, "statement", false), string(b, "subject", false),
                string(b, "kind", false), importance, sensitivity(b)));
        return Responses.json(Map.of("fact", fact(f)));
    }

    /** {@code {"id", "pinned": true | false}}. */
    @PostMapping("/api/memory/facts/pin")
    public ResponseEntity<byte[]> pin(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        UUID id = id(b);
        facts.pin(id, bool(b, "pinned", true));
        return detail(id);
    }

    /** {@code {"id", "archived": true | false}}: out of (or back into) automatic retrieval. */
    @PostMapping("/api/memory/facts/archive")
    public ResponseEntity<byte[]> archive(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        UUID id = id(b);
        facts.archive(id, bool(b, "archived", true));
        return detail(id);
    }

    /** {@code {"ids": [...]} or {"id"}, "reviewed": true | false}: the owner keeps suggested facts. */
    @PostMapping("/api/memory/facts/review")
    public ResponseEntity<byte[]> review(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        List<UUID> ids = new ArrayList<>();
        if (b.get("ids") instanceof List<?> l) {
            for (Object o : l) {
                ids.add(uuid(String.valueOf(o)));
            }
        } else {
            ids.add(id(b));
        }
        if (ids.isEmpty() || ids.size() > 500) {
            throw new ApiController.BadRequest("ids must list 1 to 500 facts");
        }
        facts.review(ids, bool(b, "reviewed", true));
        return Responses.json(Map.of("reviewed", ids.size()));
    }

    /**
     * {@code {"id"}}: proposes forgetting the fact and all its versions, answers a confirmation code; {@code {"confirm":
     * code}}: forgets.
     */
    @PostMapping("/api/memory/facts/forget")
    public ResponseEntity<byte[]> forgetFact(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        if (b.get("confirm") instanceof String code && !code.isBlank()) {
            return confirmed(forgetting.confirm(code, -1, null));
        }
        return Responses.json(proposal(forgetting.proposeFact(id(b))));
    }

    /** The proposals waiting for a confirmation (made by voice or in the app). */
    @GetMapping("/api/memory/forget")
    public ResponseEntity<byte[]> pending() {
        return Responses.json(Map.of("pending", forgetting.pending().stream().map(this::proposal).toList()));
    }

    /** {@code {"confirm": code}}: confirms a pending proposal, a voice one included. */
    @PostMapping("/api/memory/forget/confirm")
    public ResponseEntity<byte[]> confirm(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        return confirmed(forgetting.confirm(string(b, "confirm", true), -1, string(b, "phrase", false)));
    }

    /** {@code {"confirm": code}}: drops a proposal. */
    @PostMapping("/api/memory/forget/cancel")
    public ResponseEntity<byte[]> cancel(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        return Responses.json(Map.of("cancelled", forgetting.cancel(string(b, "confirm", true))));
    }

    /**
     * Forgetting everything memory holds, confirmed twice: {@code {}} answers a code and the phrase to type; {@code
     * {"confirm": code, "phrase": "forget everything"}} forgets.
     */
    @PostMapping("/api/memory/forget-everything")
    public ResponseEntity<byte[]> forgetEverything(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        if (b.get("confirm") instanceof String code && !code.isBlank()) {
            return confirmed(forgetting.confirm(code, -1, string(b, "phrase", false)));
        }
        Map<String, Object> m = proposal(forgetting.proposeEverything());
        m.put("phrase", ConfirmForgetting.EVERYTHING_PHRASE);
        return Responses.json(m);
    }

    private ResponseEntity<byte[]> confirmed(ConfirmForgetting.Outcome o) {
        if (!o.done()) {
            return Responses.json(409, Map.of("error", o.reason()));
        }
        ForgetMemory.Forgotten f = o.forgotten();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("events", f.events());
        m.put("facts", f.facts());
        m.put("stale_episodes", f.staleEpisodes());
        return Responses.json(Map.of("forgotten", m));
    }

    private Map<String, Object> proposal(ConfirmForgetting.Proposal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirm", p.code());
        m.put("query", p.query());
        m.put("everything", p.everything());
        m.put("origin", p.origin());
        m.put("facts", p.facts().stream().map(this::fact).toList());
        m.put("created_at", seconds(p.createdAt()));
        m.put("expires_at", seconds(p.expiresAt()));
        return m;
    }

    private ResponseEntity<byte[]> detail(UUID id) {
        return fact(id.toString());
    }

    // ------------------------------------------------------------------ profile

    /** The active profile and its versions ({@code ?limit=}), each with its diff to the one before. */
    @GetMapping("/api/memory/profile")
    public ResponseEntity<byte[]> profile(HttpServletRequest rq) {
        int limit = (int) ApiController.queryInt(rq, "limit", 20, 1, 200);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", browse.profile().map(this::version).orElse(null));
        List<Map<String, Object>> versions = new ArrayList<>();
        for (BrowseMemory.ProfileVersion v : browse.profileVersions(limit)) {
            Map<String, Object> e = version(v.version());
            e.put("diff", v.diff().stream().map(MemoryController::diffLine).toList());
            versions.add(e);
        }
        m.put("versions", versions);
        return Responses.json(m);
    }

    private static Map<String, Object> diffLine(ProfileText.DiffLine d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", String.valueOf(d.op()));
        m.put("text", d.text());
        return m;
    }

    /** {@code {"content": "...", "kept_lines"?: [...]}}: the owner's version (their lines are kept by every rewrite). */
    @PostMapping("/api/memory/profile")
    public ResponseEntity<byte[]> editProfile(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        List<String> pinned = new ArrayList<>();
        if (b.get("kept_lines") instanceof List<?> l) {
            l.forEach(o -> pinned.add(String.valueOf(o)));
        }
        return Responses.json(Map.of("current", version(browse.editProfile(string(b, "content", false) == null ? ""
                : string(b, "content", false), pinned))));
    }

    /** {@code {"version": id}}: that version, active again (as a new version). */
    @PostMapping("/api/memory/profile/restore")
    public ResponseEntity<byte[]> restoreProfile(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        if (!(b.get("version") instanceof Number n)) {
            throw new ApiController.BadRequest("version must be a version id");
        }
        return Responses.json(Map.of("current", version(browse.restoreProfile(n.longValue()))));
    }

    // ------------------------------------------------------------------ episodes and log

    /** {@code ?level=day|week|month&from=YYYY-MM-DD&to=YYYY-MM-DD} (to excluded; default: the last 30 days), newest first. */
    @GetMapping("/api/memory/episodes")
    public ResponseEntity<byte[]> episodes(HttpServletRequest rq) {
        EpisodeLevel level;
        try {
            level = EpisodeLevel.parse(param(rq, "level", "day"));
        } catch (IllegalArgumentException e) {
            throw new ApiController.BadRequest("level must be day, week or month");
        }
        LocalDate today = now().atZone(zone).toLocalDate();
        LocalDate to = date(rq, "to", today.plusDays(1));
        LocalDate from = date(rq, "from", to.minusDays(level == EpisodeLevel.DAY ? 30 : level == EpisodeLevel.WEEK ? 7 * 26 : 366 * 2));
        List<Map<String, Object>> out = new ArrayList<>(browse.episodes(level, from, to).stream().map(this::episode).toList());
        java.util.Collections.reverse(out);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level.wire());
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("episodes", out);
        return Responses.json(m);
    }

    /** The raw log, newest first: {@code ?q=&before=<id>&limit=}. */
    @GetMapping("/api/memory/log")
    public ResponseEntity<byte[]> log(HttpServletRequest rq) {
        long before = ApiController.queryInt(rq, "before", 0, 0, Long.MAX_VALUE);
        int limit = (int) ApiController.queryInt(rq, "limit", 100, 1, 500);
        List<MemoryEvent> events = browse.log(param(rq, "q", ""), before, limit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("events", events.stream().map(this::source).toList());
        m.put("next_before", events.size() == limit ? events.getLast().id() : null);
        return Responses.json(m);
    }

    // ------------------------------------------------------------------ export

    /** Everything memory holds, as a file: {@code ?format=json} (default) or {@code markdown}. */
    @GetMapping("/api/memory/export")
    public ResponseEntity<byte[]> export(HttpServletRequest rq) {
        String format = param(rq, "format", "json");
        ExportMemory.Export e = export.export();
        String day = now().atZone(zone).toLocalDate().toString();
        byte[] body;
        String type;
        String name;
        switch (format) {
            case "json" -> {
                body = PyJson.bytes(e.json());
                type = Responses.JSON;
                name = "marvin-memory-" + day + ".json";
            }
            case "markdown" -> {
                body = e.markdown().getBytes(StandardCharsets.UTF_8);
                type = "text/markdown; charset=utf-8";
                name = "marvin-memory-" + day + ".md";
            }
            default -> throw new ApiController.BadRequest("format must be json or markdown");
        }
        ResponseEntity<byte[]> r = Responses.send(200, body, type);
        HttpHeaders h = new HttpHeaders();
        h.putAll(r.getHeaders());
        h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
        return ResponseEntity.status(200).headers(h).body(body);
    }

    // ------------------------------------------------------------------ settings

    /** The memory settings: per-source switches ({@code collect_conversation}, {@code collect_brain}), models, hours. */
    @GetMapping("/api/memory/settings")
    public ResponseEntity<byte[]> settings() {
        return Responses.json(settingsPayload(settings.settings()));
    }

    /** Some settings changed: {@code {"collect_brain": false}}; a source switched off stops feeding the log at once. */
    @PostMapping("/api/memory/settings")
    public ResponseEntity<byte[]> updateSettings(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        Map<String, Object> changes = new LinkedHashMap<>();
        b.forEach((k, v) -> changes.put(String.valueOf(k), v));
        try {
            return Responses.json(settingsPayload(settings.update(changes)));
        } catch (MemorySettings.Invalid e) {
            throw new ApiController.BadRequest(e.getMessage());
        }
    }

    private static Map<String, Object> settingsPayload(MemorySettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", s.toMap());
        m.put("defaults", MemorySettings.DEFAULTS.toMap());
        m.put("sources", MemorySources.ALL);
        return m;
    }

    // ------------------------------------------------------------------ worker

    @GetMapping("/api/memory/worker")
    public ResponseEntity<byte[]> status() {
        return Responses.json(workerPayload());
    }

    /** {@code {"pass": "idle" | "nightly"}}: starts the pass now (it still gives way to the voice). */
    @PostMapping("/api/memory/consolidate")
    public ResponseEntity<byte[]> consolidate(HttpServletRequest rq) {
        Object body = ApiController.body(rq);
        String pass = body instanceof Map<?, ?> m && m.get("pass") instanceof String s ? s : "idle";
        ConsolidateMemory.Pass p = switch (pass) {
            case "idle" -> ConsolidateMemory.Pass.IDLE;
            case "nightly" -> ConsolidateMemory.Pass.NIGHTLY;
            default -> throw new ApiController.BadRequest("pass must be \"idle\" or \"nightly\"");
        };
        worker.consolidateNow(p);
        return Responses.json(202, workerPayload());
    }

    private Map<String, Object> workerPayload() {
        ConsolidateMemory.Status s = worker.status();
        MemoryHealth.Report h = health.health();
        MemorySettings ms = settings.settings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", s.state());
        m.put("pass", s.pass() == null ? null : s.pass().name().toLowerCase(Locale.ROOT));
        m.put("step", s.step());
        m.put("pending", s.pending());
        m.put("last_idle_at", s.lastIdleAt() > 0 ? s.lastIdleAt() : null);
        m.put("last_night_at", s.lastNightAt() > 0 ? s.lastNightAt() : null);
        m.put("next_night_at", s.nextNightAt());
        m.put("last", s.last() == null ? null : report(s.last()));
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("memory_model", ms.memoryModel());
        models.put("night_model", ms.nightModel());
        models.put("uses_voice_model", ms.memoryModel().isEmpty());
        m.put("models", models);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("model", h.embedModel());
        e.put("state", h.embedder());
        e.put("error", h.embedderError());
        e.put("fix", h.fix());
        e.put("dimensions", h.dimensions());
        e.put("search", h.searchMode());
        m.put("embeddings", e);
        m.put("events", h.events());
        m.put("facts", h.facts());
        return m;
    }

    private static Map<String, Object> report(ConsolidateMemory.Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pass", r.pass().name().toLowerCase(Locale.ROOT));
        m.put("outcome", r.outcome());
        m.put("started_at", r.startedAt());
        m.put("seconds", r.seconds());
        m.put("model", r.model());
        m.put("counts", r.counts());
        m.put("steps", r.steps());
        m.put("error", r.error());
        m.put("fix", r.fix());
        return m;
    }

    // ------------------------------------------------------------------ seeing memory

    /**
     * The graph: the owner, the people, places and things facts are about as nodes, the facts as edges (their ids;
     * the facts themselves in {@code facts}, by id).
     */
    @GetMapping("/api/memory/graph")
    public ResponseEntity<byte[]> graph() {
        VisualiseMemory.GraphView v = views.graph();
        MemoryGraph.Graph g = v.graph();
        java.util.Set<String> kept = new java.util.HashSet<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (MemoryGraph.Node n : g.nodes()) {
            kept.add(n.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("name", n.name());
            m.put("type", n.type().wire());
            m.put("facts", n.facts());
            m.put("current", n.current());
            m.put("pinned", n.pinned());
            m.put("sensitive", n.sensitive());
            m.put("past", n.past());
            nodes.add(m);
        }
        java.util.Set<UUID> used = new java.util.HashSet<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (MemoryGraph.Edge e : g.edges()) {
            used.addAll(e.facts());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", e.from());
            m.put("to", e.to());
            m.put("facts", e.facts().stream().map(UUID::toString).toList());
            m.put("mention", e.mention());
            m.put("current", e.current());
            edges.add(m);
        }
        Map<String, Object> byId = new LinkedHashMap<>();
        for (Fact f : v.facts()) {
            if (used.contains(f.id()) || kept.contains(MemoryGraph.nodeId(f.subject()))) {
                Map<String, Object> m = fact(f);
                m.put("node", MemoryGraph.nodeId(f.subject()));
                byId.put(f.id().toString(), m);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", nodes);
        m.put("edges", edges);
        m.put("facts", byId);
        m.put("total_facts", g.facts());
        m.put("hidden_nodes", g.hiddenNodes());
        return Responses.json(m);
    }

    /**
     * The meaning map: each current, not archived fact placed by its embedding, projected to two dimensions on the
     * host (principal components); the facts without an embedding apart.
     */
    @GetMapping("/api/memory/map")
    public ResponseEntity<byte[]> map() {
        VisualiseMemory.MapView v = views.map();
        List<Map<String, Object>> points = new ArrayList<>();
        for (VisualiseMemory.Point p : v.points()) {
            Map<String, Object> m = fact(p.fact());
            m.put("x", Math.round(p.x() * 1e5) / 1e5);
            m.put("y", Math.round(p.y() * 1e5) / 1e5);
            points.add(m);
        }
        MemoryHealth.Report h = health.health();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("model", h.embedModel());
        e.put("state", h.embedder());
        e.put("fix", h.fix());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("points", points);
        m.put("unplaced", v.unplaced().stream().map(this::fact).toList());
        m.put("explained", List.of(Math.round(v.explained()[0] * 1000) / 1000.0, Math.round(v.explained()[1] * 1000) / 1000.0));
        m.put("version", v.version());
        m.put("embeddings", e);
        return Responses.json(m);
    }

    /** {@code ?range=week|month|year|all}: facts as bars in time, in lanes of facts that follow one another; days and weeks. */
    @GetMapping("/api/memory/timeline")
    public ResponseEntity<byte[]> timeline(HttpServletRequest rq) {
        VisualiseMemory.Range range;
        try {
            range = VisualiseMemory.Range.parse(param(rq, "range", "month"));
        } catch (IllegalArgumentException e) {
            throw new ApiController.BadRequest(e.getMessage());
        }
        VisualiseMemory.TimelineView v = views.timeline(range);
        List<Map<String, Object>> lanes = new ArrayList<>();
        for (FactTimeline.Lane l : v.lanes()) {
            List<Map<String, Object>> bars = new ArrayList<>();
            for (FactTimeline.Bar b : l.bars()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fact", fact(b.fact()));
                m.put("start", seconds(b.start()));
                m.put("end", seconds(b.end()));
                m.put("start_kind", b.startKind());
                m.put("end_kind", b.endKind());
                m.put("next", b.next() == null ? null : b.next().toString());
                m.put("next_kind", b.nextKind());
                bars.add(m);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", l.subject());
            m.put("bars", bars);
            lanes.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("range", range.name().toLowerCase(Locale.ROOT));
        m.put("from", seconds(v.from()));
        m.put("to", seconds(v.to()));
        m.put("lanes", lanes);
        m.put("episodes", v.episodes().stream().map(this::episode).toList());
        m.put("truncated", v.truncated());
        return Responses.json(m);
    }

    /** How memory is built: the log per source, what waits to be read, what became facts, the profile; the worker. */
    @GetMapping("/api/memory/flow")
    public ResponseEntity<byte[]> flow() {
        VisualiseMemory.FlowView v = views.flow();
        List<Map<String, Object>> sources = new ArrayList<>();
        long waiting = 0;
        for (VisualiseMemory.Source s : v.sources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", s.source());
            m.put("events", s.events());
            m.put("waiting", s.waiting());
            waiting += s.waiting();
            sources.add(m);
        }
        VisualiseMemory.FactCounts c = v.facts();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stored", c.stored());
        f.put("current", c.current());
        f.put("archived", c.archived());
        f.put("pinned", c.pinned());
        f.put("suggested", c.suggested());
        f.put("sensitive", c.sensitive());
        f.put("updated", c.updated());
        f.put("invalidated", c.invalidated());
        f.put("forgotten", c.forgotten());
        VisualiseMemory.Written w = v.written();
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("profile_versions", w.profileVersions());
        written.put("active_profile", w.activeProfile());
        written.put("active_profile_at", seconds(w.activeProfileAt()));
        written.put("days", w.days());
        written.put("weeks", w.weeks());
        written.put("months", w.months());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sources", sources);
        m.put("waiting", waiting);
        m.put("facts", f);
        m.put("written", written);
        m.put("worker", workerPayload());
        m.put("recent", v.recent().stream().map(MemoryController::report).toList());
        return Responses.json(m);
    }

    // ------------------------------------------------------------------ views

    private static Double seconds(Instant t) {
        return t == null ? null : t.getEpochSecond() + t.getNano() / 1e9;
    }

    Map<String, Object> fact(Fact f) {
        Instant now = now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id().toString());
        m.put("subject", f.subject());
        m.put("statement", f.statement());
        m.put("kind", f.kind().wire());
        m.put("importance", f.importance());
        m.put("confidence", Math.round(f.confidence() * 1000) / 1000.0);
        m.put("sensitivity", f.sensitivity().wire());
        m.put("status", MemoryText.status(f, now));
        m.put("when", MemoryText.validity(f, zone));
        m.put("valid_from", seconds(f.validFrom()));
        m.put("valid_to", seconds(f.validTo()));
        m.put("learned_at", seconds(f.learnedAt()));
        m.put("expired_at", seconds(f.expiredAt()));
        m.put("superseded_by", f.supersededBy() == null ? null : f.supersededBy().toString());
        m.put("last_used_at", seconds(f.lastUsedAt()));
        m.put("use_count", f.useCount());
        m.put("archived", f.archived());
        m.put("pinned", f.pinned());
        m.put("origin", f.origin().wire());
        m.put("extracted_by", f.extractedBy());
        m.put("reviewed", f.reviewed());
        m.put("reviewed_at", seconds(f.reviewedAt()));
        m.put("sources", f.sources());
        return m;
    }

    /** A log event as a fact's source: quoted text, date, and where it was said. */
    Map<String, Object> source(MemoryEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("ts", seconds(e.ts()));
        m.put("source", e.source());
        m.put("kind", e.kind());
        m.put("sensitivity", e.sensitivity().wire());
        m.put("text", e.body());
        String ref = e.externalRef() == null ? "" : e.externalRef();
        if (ref.startsWith("conversation:")) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("day", e.ts().atZone(zone).toLocalDate().toString());
            try {
                c.put("entry", Long.parseLong(ref.substring("conversation:".length())));
            } catch (NumberFormatException x) {
                c.put("entry", null);
            }
            m.put("conversation", c);
        }
        return m;
    }

    Map<String, Object> version(BlockVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("content", v.content());
        m.put("tokens", v.tokens());
        m.put("status", v.status().wire());
        m.put("rationale", v.rationale());
        m.put("author", v.author().wire());
        m.put("created_at", seconds(v.createdAt()));
        m.put("kept_lines", v.keptLines());
        m.put("evidence", v.evidence());
        return m;
    }

    Map<String, Object> episode(Episode e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("level", e.level().wire());
        m.put("day", e.day().toString());
        m.put("period_start", seconds(e.periodStart()));
        m.put("period_end", seconds(e.periodEnd()));
        m.put("summary", e.summary());
        m.put("stale", e.stale());
        m.put("events", e.events());
        m.put("created_at", seconds(e.createdAt()));
        return m;
    }

    // ------------------------------------------------------------------ request helpers

    private static String param(HttpServletRequest rq, String key, String dflt) {
        String v = QueryString.first(QueryString.parse(rq.getQueryString()), key);
        return v == null ? dflt : v.strip();
    }

    private static LocalDate date(HttpServletRequest rq, String key, LocalDate dflt) {
        String v = param(rq, key, "");
        if (v.isEmpty()) {
            return dflt;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeException e) {
            throw new ApiController.BadRequest(key + " must be YYYY-MM-DD");
        }
    }

    private static Map<?, ?> object(HttpServletRequest rq) {
        if (ApiController.body(rq) instanceof Map<?, ?> m) {
            return m;
        }
        throw new ApiController.BadRequest("send a JSON object");
    }

    private static String string(Map<?, ?> b, String key, boolean required) {
        Object v = b.get(key);
        if (v == null) {
            if (required) {
                throw new ApiController.BadRequest(key + " is required");
            }
            return null;
        }
        if (!(v instanceof String s)) {
            throw new ApiController.BadRequest(key + " must be a string");
        }
        if (s.length() > 4000) {
            throw new ApiController.BadRequest(key + " is too long");
        }
        return s;
    }

    private static boolean bool(Map<?, ?> b, String key, boolean dflt) {
        Object v = b.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Boolean x) {
            return x;
        }
        throw new ApiController.BadRequest(key + " must be true or false");
    }

    private static Sensitivity sensitivity(Map<?, ?> b) {
        String s = string(b, "sensitivity", false);
        if (s == null) {
            return null;
        }
        Sensitivity v = Sensitivity.parse(s, null);
        if (v == null || v == Sensitivity.SECRET) {
            throw new ApiController.BadRequest("sensitivity must be normal, personal or sensitive");
        }
        return v;
    }

    private static UUID id(Map<?, ?> b) {
        return uuid(string(b, "id", true));
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s.strip());
        } catch (IllegalArgumentException e) {
            throw new NotFound("no such fact");
        }
    }
}

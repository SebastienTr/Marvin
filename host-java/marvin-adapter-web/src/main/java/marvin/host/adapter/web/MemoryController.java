// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.in.MemoryHealth;

/**
 * The memory worker in the app (the first part of the memory API): its state and last report, and "Consolidate now".
 * Behind the same access rules as the rest of the API.
 */
@RestController
public class MemoryController {
    private final ConsolidateMemory worker;
    private final MemoryHealth health;

    public MemoryController(ConsolidateMemory worker, MemoryHealth health) {
        this.worker = worker;
        this.health = health;
    }

    @GetMapping("/api/memory/worker")
    public ResponseEntity<byte[]> status() {
        return Responses.json(payload());
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
        return Responses.json(202, payload());
    }

    private Map<String, Object> payload() {
        ConsolidateMemory.Status s = worker.status();
        MemoryHealth.Report h = health.health();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", s.state());
        m.put("pass", s.pass() == null ? null : s.pass().name().toLowerCase(Locale.ROOT));
        m.put("step", s.step());
        m.put("pending", s.pending());
        m.put("last_idle_at", s.lastIdleAt() > 0 ? s.lastIdleAt() : null);
        m.put("last_night_at", s.lastNightAt() > 0 ? s.lastNightAt() : null);
        m.put("next_night_at", s.nextNightAt());
        m.put("last", s.last() == null ? null : report(s.last()));
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
}

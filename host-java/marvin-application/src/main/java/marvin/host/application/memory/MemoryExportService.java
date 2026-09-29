// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import marvin.host.application.memory.port.in.ExportMemory;
import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.shared.Clocks;

/** Everything memory holds, as JSON-like tables and as readable Markdown (docs/design.md 2.6). */
public final class MemoryExportService implements ExportMemory {
    private final EventLog events;
    private final FactStore facts;
    private final EpisodeStore episodes;
    private final ProfileStore profiles;
    private final MemorySettingsService settings;
    private final Clocks clocks;

    public MemoryExportService(EventLog events, FactStore facts, EpisodeStore episodes, ProfileStore profiles,
                               MemorySettingsService settings, Clocks clocks) {
        this.events = events;
        this.facts = facts;
        this.episodes = episodes;
        this.profiles = profiles;
        this.settings = settings;
        this.clocks = clocks;
    }

    @Override
    public Export export() {
        Instant now = EventFeeds.instant(clocks.wallSeconds());
        List<Map<String, Object>> log = new ArrayList<>();
        for (long after = 0; ; ) {
            List<MemoryEvent> page = events.page(after, 1000);
            if (page.isEmpty()) {
                break;
            }
            page.forEach(e -> log.add(event(e)));
            after = page.getLast().id();
        }
        List<Fact> all = facts.list(new FactStore.Query("", "", "", "all", null, null, null, now, Integer.MAX_VALUE, 0));
        List<Episode> eps = new ArrayList<>();
        for (EpisodeLevel l : EpisodeLevel.values()) {
            eps.addAll(episodes.list(l, LocalDate.of(1970, 1, 1), LocalDate.of(9999, 1, 1)));
        }
        List<BlockVersion> versions = profiles.versions(Block.PROFILE, Integer.MAX_VALUE);
        Map<String, List<Map<String, Object>>> json = new LinkedHashMap<>();
        json.put("event_log", log);
        json.put("fact", all.stream().map(MemoryExportService::fact).toList());
        json.put("episode", eps.stream().map(MemoryExportService::episode).toList());
        json.put("block_version", versions.stream().map(MemoryExportService::version).toList());
        json.put("settings", List.of(settings.settings().toMap()));
        return new Export(json, markdown(all, eps, versions, now));
    }

    static Map<String, Object> event(MemoryEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("ts", e.ts().toString());
        m.put("recorded_at", e.recordedAt() == null ? null : e.recordedAt().toString());
        m.put("source", e.source());
        m.put("kind", e.kind());
        m.put("sensitivity", e.sensitivity().wire());
        m.put("external_ref", e.externalRef());
        m.put("body", e.body());
        m.put("data", e.data());
        m.put("consolidated_at", e.consolidatedAt() == null ? null : e.consolidatedAt().toString());
        return m;
    }

    static Map<String, Object> fact(Fact f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id().toString());
        m.put("subject", f.subject());
        m.put("statement", f.statement());
        m.put("kind", f.kind().wire());
        m.put("importance", f.importance());
        m.put("confidence", f.confidence());
        m.put("sensitivity", f.sensitivity().wire());
        m.put("valid_from", str(f.validFrom()));
        m.put("valid_to", str(f.validTo()));
        m.put("learned_at", str(f.learnedAt()));
        m.put("expired_at", str(f.expiredAt()));
        m.put("superseded_by", f.supersededBy() == null ? null : f.supersededBy().toString());
        m.put("last_used_at", str(f.lastUsedAt()));
        m.put("use_count", f.useCount());
        m.put("archived", f.archived());
        m.put("pinned", f.pinned());
        m.put("origin", f.origin().wire());
        m.put("extracted_by", f.extractedBy());
        m.put("reviewed_at", str(f.reviewedAt()));
        m.put("sources", f.sources());
        return m;
    }

    static Map<String, Object> episode(Episode e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("level", e.level().wire());
        m.put("day", e.day().toString());
        m.put("period_start", str(e.periodStart()));
        m.put("period_end", str(e.periodEnd()));
        m.put("summary", e.summary());
        m.put("stale", e.stale());
        m.put("created_at", str(e.createdAt()));
        m.put("events", e.events());
        return m;
    }

    static Map<String, Object> version(BlockVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("block", v.block().wire());
        m.put("content", v.content());
        m.put("tokens", v.tokens());
        m.put("status", v.status().wire());
        m.put("rationale", v.rationale());
        m.put("evidence", v.evidence());
        m.put("author", v.author().wire());
        m.put("created_at", str(v.createdAt()));
        m.put("decided_at", str(v.decidedAt()));
        m.put("kept_lines", v.keptLines());
        return m;
    }

    private static String str(Instant t) {
        return t == null ? null : t.toString();
    }

    static String markdown(List<Fact> all, List<Episode> eps, List<BlockVersion> versions, Instant now) {
        StringBuilder b = new StringBuilder("# Marvin's memory\n\nExported ").append(now).append(".\n\n## Profile\n\n");
        versions.stream().filter(v -> v.status() == BlockVersion.Status.ACTIVE).findFirst()
                .ifPresentOrElse(v -> b.append(v.content()).append("\n\n"), () -> b.append("(none yet)\n\n"));
        b.append("## Facts\n\n");
        Map<String, List<Fact>> bySubject = new TreeMap<>();
        for (Fact f : all) {
            if (f.current(now)) {
                bySubject.computeIfAbsent(f.subject(), k -> new ArrayList<>()).add(f);
            }
        }
        if (bySubject.isEmpty()) {
            b.append("(none yet)\n\n");
        }
        bySubject.forEach((subject, fs) -> {
            b.append("### ").append(subject).append("\n\n");
            for (Fact f : fs) {
                b.append("- ").append(f.statement());
                List<String> tags = new ArrayList<>();
                if (f.pinned()) {
                    tags.add("pinned");
                }
                if (f.archived()) {
                    tags.add("archived");
                }
                if (f.sensitivity().ordinal() > 0) {
                    tags.add(f.sensitivity().wire());
                }
                tags.add("learned " + f.learnedAt().toString().substring(0, 10));
                tags.add(f.origin() == marvin.host.domain.memory.FactOrigin.OWNER ? "stated by you" : "from events " + f.sources());
                b.append(" (").append(String.join(", ", tags)).append(")\n");
            }
            b.append('\n');
        });
        List<Fact> past = all.stream().filter(f -> !f.current(now) && f.supersededBy() == null).toList();
        if (!past.isEmpty()) {
            b.append("## No longer true\n\n");
            for (Fact f : past) {
                b.append("- ").append(f.subject()).append(": ").append(f.statement()).append(" (until ")
                        .append(f.validTo() == null ? "?" : f.validTo().toString().substring(0, 10)).append(")\n");
            }
            b.append('\n');
        }
        b.append("## Episodes\n\n");
        for (EpisodeLevel l : EpisodeLevel.values()) {
            for (Episode e : eps) {
                if (e.level() == l) {
                    b.append("### ").append(l.wire()).append(" of ").append(e.day()).append("\n\n").append(e.summary()).append("\n\n");
                }
            }
        }
        return b.toString();
    }
}

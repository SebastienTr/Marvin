// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import marvin.host.application.memory.port.in.BrowseMemory;
import marvin.host.application.memory.port.in.ForgetMemory;
import marvin.host.application.memory.port.in.ManageFacts;
import marvin.host.application.memory.port.in.MemoryHealth;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.MemoryListener;
import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.FactOrigin;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.memory.ProfileText;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Redaction;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * What the owner does with memory (docs/design.md 5.6 and 2.2): facts (list, sources, remember, edit, pin,
 * archive), the profile (versions, edit, restore), episodes, the raw log, forgetting, and the health report.
 * Every change is an owner event in the log first, so the worker never undoes it; forgetting leaves nothing of what
 * was forgotten in that event.
 */
public final class MemoryAdminService implements ManageFacts, ForgetMemory, BrowseMemory, MemoryHealth {
    private static final Logger log = Logger.getLogger("marvin.memory");
    /** Importance of a fact the owner states. */
    static final int OWNER_IMPORTANCE = 8;

    private final EventLog events;
    private final FactStore facts;
    private final EpisodeStore episodes;
    private final ProfileStore profiles;
    private final Embeddings embeddings;
    private final MemoryConfig config;
    private final LocalDays days;
    private final Clocks clocks;
    private final Supplier<UUID> ids;
    private final List<MemoryListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public MemoryAdminService(EventLog events, FactStore facts, EpisodeStore episodes, ProfileStore profiles,
                              Embeddings embeddings, MemoryConfig config, LocalDays days, Clocks clocks, Supplier<UUID> ids) {
        this.events = events;
        this.facts = facts;
        this.episodes = episodes;
        this.profiles = profiles;
        this.embeddings = embeddings;
        this.config = config;
        this.days = days;
        this.clocks = clocks;
        this.ids = ids;
    }

    public void addListener(MemoryListener l) {
        listeners.add(l);
    }

    private Instant now() {
        return EventFeeds.instant(clocks.wallSeconds());
    }

    /** An owner event, written now (the app waits for it: its id becomes a fact's source). */
    private MemoryEvent owner(String kind, String body, Map<String, Object> data, Sensitivity s) {
        MemoryEvent draft = MemoryEvent.draft(now(), MemorySources.OWNER, kind, s, "owner:" + ids.get(),
                Redaction.redact(body), data);
        return events.appendOne(draft).orElseThrow();
    }

    private void changed(String what) {
        for (MemoryListener l : listeners) {
            try {
                l.onMemory("changed", Map.of("what", what));
            } catch (RuntimeException e) {
                log.warning("memory listener failed: " + e);
            }
        }
    }

    private float[] embedOrNull(String text) {
        try {
            return embeddings.embed(text);
        } catch (Embedder.Unavailable e) {
            return null;                    // written without; the nightly pass embeds it
        }
    }

    // ------------------------------------------------------------------ facts

    @Override
    public Page list(FactStore.Query query) {
        return new Page(facts.list(query), facts.count(query));
    }

    @Override
    public Optional<Detail> get(UUID id) {
        return facts.get(id).map(f -> new Detail(f, events.byIds(f.sources()), facts.versions(id)));
    }

    @Override
    public Fact remember(String statement, String subject, Sensitivity sensitivity) {
        FactCandidate c = checked(statement, subject, sensitivity);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("subject", c.subject());
        MemoryEvent e = owner(MemorySources.REMEMBER, c.statement(), d, c.sensitivity());
        Instant now = now();
        Fact f = new Fact(ids.get(), c.subject(), c.statement(), FactKind.STATE, OWNER_IMPORTANCE, 1.0, c.sensitivity(), now,
                null, now, null, null, null, 0, false, false, FactOrigin.OWNER, "", List.of(e.id()));
        Map<UUID, float[]> v = new LinkedHashMap<>();
        v.put(f.id(), embedOrNull(f.embeddingText()));
        facts.apply(new Reconciliation.Plan(List.of(f), List.of(), Map.of(), new Operation.Add()), v);
        changed("facts");
        return f;
    }

    private static FactCandidate checked(String statement, String subject, Sensitivity sensitivity) {
        FactCandidate.Checked c = FactCandidate.check(new FactCandidate.Raw(subject, statement, null, null, null, 8,
                sensitivity == null ? "normal" : sensitivity.wire(), 1.0), java.time.ZoneOffset.UTC, Sensitivity.NORMAL);
        return switch (c) {
            case FactCandidate.Accepted a -> a.candidate();
            case FactCandidate.Dropped d -> throw new IllegalArgumentException("secret".equals(d.reason())
                    ? "that looks like a password or a code: memory never keeps those" : "a fact needs a statement");
        };
    }

    @Override
    public Fact edit(UUID id, Edit edit) {
        Fact old = facts.get(id).orElseThrow(() -> new IllegalArgumentException("no such fact"));
        if (old.expiredAt() != null) {
            throw new IllegalArgumentException("only the current version of a fact can be edited");
        }
        String statement = edit.statement() != null ? edit.statement() : old.statement();
        String subject = edit.subject() != null ? edit.subject() : old.subject();
        Sensitivity sens = edit.sensitivity() != null ? edit.sensitivity() : old.sensitivity();
        FactCandidate c = checked(statement, subject, sens);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fact", id.toString());
        MemoryEvent e = owner(MemorySources.EDIT, c.statement(), d, c.sensitivity());
        Instant now = now();
        List<Long> sources = new ArrayList<>(new LinkedHashSet<>(old.sources()));
        sources.add(e.id());
        Fact next = new Fact(ids.get(), c.subject(), c.statement(), edit.kind() != null ? FactKind.parse(edit.kind()) : old.kind(),
                edit.importance() != null ? edit.importance() : old.importance(), 1.0, edit.sensitivity() != null ? sens : c.sensitivity(),
                old.validFrom(), old.validTo(), now, null, null, old.lastUsedAt(), old.useCount(), old.archived(), old.pinned(),
                FactOrigin.OWNER, "", sources);
        Map<UUID, float[]> v = new LinkedHashMap<>();
        v.put(next.id(), embedOrNull(next.embeddingText()));
        facts.apply(new Reconciliation.Plan(List.of(next), List.of(new Reconciliation.Expiry(old.id(), now, old.validTo(), next.id())),
                Map.of(), new Operation.Update(old.id(), c.statement())), v);
        changed("facts");
        return next;
    }

    @Override
    public void pin(UUID id, boolean pinned) {
        facts.get(id).orElseThrow(() -> new IllegalArgumentException("no such fact"));
        owner(MemorySources.PIN, "", Map.of("fact", id.toString(), "pinned", pinned), Sensitivity.NORMAL);
        facts.setPinned(id, pinned);
        changed("facts");
    }

    @Override
    public void archive(UUID id, boolean archived) {
        facts.get(id).orElseThrow(() -> new IllegalArgumentException("no such fact"));
        owner(MemorySources.EDIT, "", Map.of("fact", id.toString(), "archived", archived), Sensitivity.NORMAL);
        facts.setArchived(List.of(id), archived);
        changed("facts");
    }

    @Override
    public void review(java.util.Collection<UUID> ids, boolean reviewed) {
        List<UUID> known = ids.stream().distinct().filter(id -> facts.get(id).isPresent()).toList();
        if (known.isEmpty()) {
            throw new IllegalArgumentException("no such fact");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("facts", known.stream().map(UUID::toString).toList());
        d.put("reviewed", reviewed);
        owner(MemorySources.REVIEW, "", d, Sensitivity.NORMAL);
        facts.setReviewed(known, reviewed ? now() : null);
        changed("facts");
    }

    // ------------------------------------------------------------------ forgetting

    @Override
    public Forgotten forgetFact(UUID id) {
        List<Fact> versions = facts.versions(id);
        if (versions.isEmpty()) {
            return new Forgotten(0, 0, 0);
        }
        List<UUID> all = versions.stream().map(Fact::id).toList();
        int n = facts.delete(all);
        for (Fact f : versions) {
            dropFromProfile(f.statement());
        }
        owner(MemorySources.FORGET, "", Map.of("facts", n), Sensitivity.NORMAL);
        changed("facts");
        return new Forgotten(0, n, 0);
    }

    /** A forgotten fact leaves the profile at once (a new version without its lines), not only at the next rewrite. */
    private void dropFromProfile(String statement) {
        profiles.active(Block.PROFILE).ifPresent(p -> {
            String text = ProfileText.withoutLinesLike(p.content(), statement);
            if (!text.equals(p.content())) {
                List<String> kept = p.keptLines().stream().filter(l -> ProfileText.lines(text).contains(l.strip())).toList();
                Instant now = now();
                profiles.add(new BlockVersion(0, Block.PROFILE, text, config.tokens().estimate(text), BlockVersion.Status.ACTIVE,
                        "a forgotten fact removed", List.of(), BlockVersion.Author.OWNER, now, now, kept));
            }
        });
    }

    @Override
    public Forgotten forgetEvents(Instant from, Instant to) {
        int n = events.deleteBetween(from, to);
        int f = facts.deleteOrphans();
        int s = episodes.markStale(from, to);
        owner(MemorySources.FORGET, "", Map.of("events", n, "facts", f), Sensitivity.NORMAL);
        changed("log");
        return new Forgotten(n, f, s);
    }

    @Override
    public Forgotten forgetEvent(long id) {
        List<MemoryEvent> e = events.byIds(List.of(id));
        if (e.isEmpty()) {
            return new Forgotten(0, 0, 0);
        }
        int n = events.delete(List.of(id));
        int f = facts.deleteOrphans();
        LocalDate d = e.getFirst().ts().atZone(days.zone()).toLocalDate();
        int s = episodes.markStale(d.atStartOfDay(days.zone()).toInstant(), d.plusDays(1).atStartOfDay(days.zone()).toInstant());
        owner(MemorySources.FORGET, "", Map.of("events", n, "facts", f), Sensitivity.NORMAL);
        changed("log");
        return new Forgotten(n, f, s);
    }

    @Override
    public Forgotten forgetEverything() {
        long n = events.count();
        long f = facts.count(all());
        facts.deleteAll();
        episodes.deleteAll();
        profiles.deleteAll();
        events.deleteAll();
        log.info("memory: everything forgotten at the owner's request");
        changed("all");
        return new Forgotten((int) n, (int) f, 0);
    }

    FactStore.Query all() {
        return new FactStore.Query("", "", "", "all", null, null, null, now(), Integer.MAX_VALUE, 0);
    }

    // ------------------------------------------------------------------ profile, episodes, log

    @Override
    public Optional<BlockVersion> profile() {
        return profiles.active(Block.PROFILE);
    }

    @Override
    public List<ProfileVersion> profileVersions(int limit) {
        List<BlockVersion> vs = profiles.versions(Block.PROFILE, limit + 1);
        List<ProfileVersion> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, vs.size()); i++) {
            String before = i + 1 < vs.size() ? vs.get(i + 1).content() : "";
            out.add(new ProfileVersion(vs.get(i), ProfileText.diff(before, vs.get(i).content())));
        }
        return out;
    }

    @Override
    public BlockVersion editProfile(String content, List<String> pinnedLines) {
        String text = String.join("\n", ProfileText.lines(content == null ? "" : content));
        int tokens = config.tokens().estimate(text);
        if (tokens > Block.PROFILE.maxTokens()) {
            throw new IllegalArgumentException("the profile is limited to " + Block.PROFILE.maxTokens()
                    + " tokens (this one is about " + tokens + ")");
        }
        Optional<BlockVersion> prev = profiles.active(Block.PROFILE);
        List<String> kept = new ArrayList<>(ProfileText.keptAfterOwnerEdit(prev.map(BlockVersion::content).orElse(""),
                prev.map(BlockVersion::keptLines).orElse(List.of()), text));
        List<String> lines = ProfileText.lines(text);
        for (String p : pinnedLines == null ? List.<String>of() : pinnedLines) {
            if (lines.contains(p.strip()) && !kept.contains(p.strip())) {
                kept.add(p.strip());
            }
        }
        Instant now = now();
        BlockVersion v = profiles.add(new BlockVersion(0, Block.PROFILE, text, tokens, BlockVersion.Status.ACTIVE,
                "written by the owner", List.of(), BlockVersion.Author.OWNER, now, now, kept));
        owner(MemorySources.PROFILE_EDIT, "", Map.of("version", v.id()), Sensitivity.NORMAL);
        changed("profile");
        return v;
    }

    @Override
    public BlockVersion restoreProfile(long versionId) {
        BlockVersion old = profiles.get(versionId).filter(v -> v.block() == Block.PROFILE)
                .orElseThrow(() -> new IllegalArgumentException("no such profile version"));
        Instant now = now();
        BlockVersion v = profiles.add(new BlockVersion(0, Block.PROFILE, old.content(), old.tokens(), BlockVersion.Status.ACTIVE,
                "restored version " + versionId, List.of(), BlockVersion.Author.OWNER, now, now, old.keptLines()));
        owner(MemorySources.PROFILE_EDIT, "", Map.of("version", v.id(), "restored", versionId), Sensitivity.NORMAL);
        changed("profile");
        return v;
    }

    @Override
    public List<Episode> episodes(EpisodeLevel level, LocalDate from, LocalDate to) {
        return episodes.list(level, from, to);
    }

    @Override
    public List<MemoryEvent> log(String query, long beforeId, int limit) {
        return events.recent(query == null ? "" : query, beforeId, Math.clamp(limit, 1, 500));
    }

    // ------------------------------------------------------------------ health

    @Override
    public Report health() {
        return new Report(facts.searchMode(), facts.dimensions(), embeddings.model(), embeddings.state(), embeddings.error(),
                embeddings.fix(), events.count(), facts.count(new FactStore.Query("", "", "", "current", null, null, null,
                now(), Integer.MAX_VALUE, 0)), events.unconsolidatedCount());
    }
}

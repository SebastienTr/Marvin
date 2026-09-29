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
import marvin.host.application.memory.port.out.MemoryStateStore;
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
 * was forgotten in that event. Every change runs under the {@link MemoryGuard}: a memory pass that decided its
 * writes before it does not write them.
 *
 * <p>Forgetting a fact takes it out of every future context: its versions are deleted; the lines it was learned
 * from are withheld (never summarised, recalled, listed or exported again; the conversation itself stays in
 * History); the summaries of their days, weeks and months are blanked and written again without them; the profile's
 * lines that state it leave the active version and every older one at once, and the statement waits for the next
 * profile rewrite, which removes reworded lines too.
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
    private final MemoryGuard guard;
    private final MemoryStateStore state;
    private final List<MemoryListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public MemoryAdminService(EventLog events, FactStore facts, EpisodeStore episodes, ProfileStore profiles,
                              Embeddings embeddings, MemoryConfig config, LocalDays days, Clocks clocks, Supplier<UUID> ids,
                              MemoryGuard guard, MemoryStateStore state) {
        this.guard = guard;
        this.state = state;
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

    private final List<Runnable> forgetListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Called after anything is forgotten (the voice drops its history, which may still state it). */
    public void addForgetListener(Runnable r) {
        forgetListeners.add(r);
    }

    private void forgotten() {
        for (Runnable r : forgetListeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warning("forget listener failed: " + e);
            }
        }
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
        guard.ownerRun(() -> facts.apply(new Reconciliation.Plan(List.of(f), List.of(), Map.of(), new Operation.Add()), v));
        changed("facts");
        return f;
    }

    @Override
    public Fact suggest(String statement, String subject, Sensitivity sensitivity) {
        FactCandidate c = checked(statement, subject, sensitivity);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("subject", c.subject());
        d.put("others_present", true);
        MemoryEvent e = owner(MemorySources.REMEMBER, c.statement(), d, c.sensitivity());
        Instant now = now();
        Fact f = new Fact(ids.get(), c.subject(), c.statement(), FactKind.STATE, 5, 0.6, c.sensitivity(), now, null, now, null,
                null, null, 0, false, false, FactOrigin.EXTRACTED, "remember, said with someone else in the room", List.of(e.id()));
        Map<UUID, float[]> v = new LinkedHashMap<>();
        v.put(f.id(), embedOrNull(f.embeddingText()));
        guard.ownerRun(() -> facts.apply(new Reconciliation.Plan(List.of(f), List.of(), Map.of(), new Operation.Add()), v));
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
        boolean reworded = !c.statement().equals(old.statement());
        boolean madeSensitive = next.sensitivity().ordinal() >= Sensitivity.SENSITIVE.ordinal()
                && old.sensitivity().ordinal() < Sensitivity.SENSITIVE.ordinal();
        try {
            guard.ownerRun(() -> {
                facts.apply(new Reconciliation.Plan(List.of(next), List.of(new Reconciliation.Expiry(old.id(), now, old.validTo(),
                        next.id())), Map.of(), new Operation.Update(old.id(), c.statement())), v);
                if (madeSensitive) {
                    // what it was learned from is sensitive too: out of the day summaries and of a guest's hearing
                    List<MemoryEvent> src = events.byIds(old.sources());
                    events.relabel(old.sources(), Sensitivity.SENSITIVE);
                    staleDays(src);
                }
                if (reworded || madeSensitive) {
                    withdrawFromProfile(old.statement());     // the profile is heard by everyone in the room
                }
            });
        } catch (FactStore.Conflict conflict) {
            throw new IllegalArgumentException("this fact was changed meanwhile: open it again");
        }
        changed("facts");
        return next;
    }

    @Override
    public void pin(UUID id, boolean pinned) {
        facts.get(id).orElseThrow(() -> new IllegalArgumentException("no such fact"));
        guard.ownerRun(() -> {
            owner(MemorySources.PIN, "", Map.of("fact", id.toString(), "pinned", pinned), Sensitivity.NORMAL);
            facts.setPinned(id, pinned);
        });
        changed("facts");
    }

    @Override
    public void archive(UUID id, boolean archived) {
        Fact f = facts.get(id).orElseThrow(() -> new IllegalArgumentException("no such fact"));
        guard.ownerRun(() -> {
            owner(MemorySources.EDIT, "", Map.of("fact", id.toString(), "archived", archived), Sensitivity.NORMAL);
            facts.setArchived(List.of(id), archived);
            if (archived) {
                withdrawFromProfile(f.statement());
            }
        });
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
        guard.ownerRun(() -> {
            owner(MemorySources.REVIEW, "", d, Sensitivity.NORMAL);
            facts.setReviewed(known, reviewed ? now() : null);
        });
        changed("facts");
    }

    // ------------------------------------------------------------------ forgetting

    @Override
    public Forgotten forgetFact(UUID id) {
        Forgotten out = guard.owner(() -> {
            List<Fact> versions = facts.versions(id);
            if (versions.isEmpty()) {
                return new Forgotten(0, 0, 0);
            }
            List<Long> sources = versions.stream().flatMap(f -> f.sources().stream()).distinct().toList();
            List<MemoryEvent> src = events.byIds(sources);
            int n = facts.delete(versions.stream().map(Fact::id).toList());
            forgetSources(src);
            int stale = staleDays(src);
            List<String> statements = versions.stream().map(Fact::statement).distinct().toList();
            forgetInProfile(statements);
            owner(MemorySources.FORGET, "", Map.of("facts", n), Sensitivity.NORMAL);
            return new Forgotten(0, n, stale);
        });
        changed("facts");
        forgotten();
        return out;
    }

    /**
     * The lines a forgotten fact was learned from: withheld from summaries, recall, the raw log and the export; an
     * owner event holds only the statement itself, so its text goes.
     */
    private void forgetSources(List<MemoryEvent> src) {
        events.withhold(src.stream().map(MemoryEvent::id).toList());
        for (MemoryEvent e : src) {
            if (MemorySources.OWNER.equals(e.source()) && !e.body().isEmpty()) {
                events.redact(e.id(), "");
            }
        }
    }

    /** Blanks the summaries of the events' days (and their weeks and months), to be written again without them. */
    private int staleDays(List<MemoryEvent> evs) {
        int n = 0;
        for (LocalDate d : evs.stream().map(e -> e.ts().atZone(days.zone()).toLocalDate()).distinct().toList()) {
            n += episodes.markStale(d.atStartOfDay(days.zone()).toInstant(), d.plusDays(1).atStartOfDay(days.zone()).toInstant());
        }
        return n;
    }

    /**
     * Forgotten statements leave the profile: the lines that state them, from the active version (a new version) and
     * from every older one (so that restoring one does not bring them back); reworded lines at the next rewrite.
     */
    private void forgetInProfile(List<String> statements) {
        if (profiles.versions(Block.PROFILE, 1).isEmpty()) {
            return;
        }
        for (String st : statements) {
            dropFromProfile(st);
            ProfileRemovals.redactHistory(profiles, config.tokens(), st);
        }
        ProfileRemovals.add(state, statements);
    }

    /** A statement no longer true or no longer for everyone's ears (corrected, archived, made sensitive). */
    private void withdrawFromProfile(String statement) {
        if (profiles.active(Block.PROFILE).isEmpty()) {
            return;
        }
        dropFromProfile(statement);
        ProfileRemovals.add(state, List.of(statement));
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
        Forgotten out = guard.owner(() -> {
            int s = episodes.markStale(from, to);
            ForgottenFeed.range(state, from, to);          // a catch-up of the other contexts never brings it back
            int n = events.deleteBetween(from, to);
            int f = forgetOrphans();
            owner(MemorySources.FORGET, "", Map.of("events", n, "facts", f), Sensitivity.NORMAL);
            return new Forgotten(n, f, s);
        });
        changed("log");
        forgotten();
        return out;
    }

    /** The facts whose events were all forgotten go, and the profile's lines with them. */
    private int forgetOrphans() {
        List<Fact> orphans = facts.orphans();
        int n = facts.delete(orphans.stream().map(Fact::id).toList());
        forgetInProfile(orphans.stream().map(Fact::statement).distinct().toList());
        return n;
    }

    @Override
    public Forgotten forgetEvent(long id) {
        Forgotten out = guard.owner(() -> {
            List<MemoryEvent> e = events.byIds(List.of(id));
            if (e.isEmpty()) {
                return new Forgotten(0, 0, 0);
            }
            int s = staleDays(e);
            ForgottenFeed.event(state, e.getFirst());
            int n = events.delete(List.of(id));
            int f = forgetOrphans();
            owner(MemorySources.FORGET, "", Map.of("events", n, "facts", f), Sensitivity.NORMAL);
            return new Forgotten(n, f, s);
        });
        if (out.events() > 0) {
            changed("log");
            forgotten();
        }
        return out;
    }

    @Override
    public Forgotten forgetEverything() {
        // a reset: the pass in progress stops, and nothing it decided before is written after
        Forgotten out = guard.reset(() -> {
            long n = events.count();
            long f = facts.count(all());
            facts.deleteAll();
            episodes.deleteAll();
            profiles.deleteAll();
            events.deleteAll();
            state.put("nightly", Map.of());
            ProfileRemovals.clear(state);
            ForgottenFeed.range(state, Instant.EPOCH, now().plusSeconds(1));
            return new Forgotten((int) n, (int) f, 0);
        });
        log.info("memory: everything forgotten at the owner's request");
        changed("all");
        forgotten();
        return out;
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
        BlockVersion v = guard.owner(() -> {
            BlockVersion added = profiles.add(new BlockVersion(0, Block.PROFILE, text, tokens, BlockVersion.Status.ACTIVE,
                    "written by the owner", List.of(), BlockVersion.Author.OWNER, now, now, kept));
            owner(MemorySources.PROFILE_EDIT, "", Map.of("version", added.id()), Sensitivity.NORMAL);
            return added;
        });
        changed("profile");
        return v;
    }

    @Override
    public BlockVersion restoreProfile(long versionId) {
        BlockVersion old = profiles.get(versionId).filter(v -> v.block() == Block.PROFILE)
                .orElseThrow(() -> new IllegalArgumentException("no such profile version"));
        Instant now = now();
        BlockVersion v = guard.owner(() -> {
            // nothing forgotten or withdrawn since comes back with an older version
            String text = old.content();
            for (String r : ProfileRemovals.pending(state)) {
                text = ProfileText.withoutLinesLike(text, r);
            }
            List<String> lines = ProfileText.lines(text);
            List<String> kept = old.keptLines().stream().filter(l -> lines.contains(l.strip())).toList();
            BlockVersion added = profiles.add(new BlockVersion(0, Block.PROFILE, text, config.tokens().estimate(text),
                    BlockVersion.Status.ACTIVE, "restored version " + versionId, List.of(), BlockVersion.Author.OWNER, now, now,
                    kept));
            owner(MemorySources.PROFILE_EDIT, "", Map.of("version", added.id(), "restored", versionId), Sensitivity.NORMAL);
            return added;
        });
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

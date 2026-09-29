// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import marvin.host.application.memory.port.in.ConfirmForgetting;
import marvin.host.application.memory.port.in.ForgetMemory;
import marvin.host.application.memory.port.in.RecallMemory;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.MemoryListener;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.MemoryText;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.Clocks;

/**
 * Forgetting after a confirmation (docs/design.md 5.3 and 2.2). Proposals live in memory for a few minutes; a code
 * is used once. A proposal made by voice is confirmed in a later turn of the conversation (the owner said yes after
 * hearing what would go), or in the app, where every pending proposal is shown.
 */
public final class ForgetConfirmations implements ConfirmForgetting {
    private static final Logger log = Logger.getLogger("marvin.memory");
    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_MATCHES = 5;
    /** A fact must be at least this similar to the query to be proposed. */
    static final double MATCH_FLOOR = 0.5;

    private final FactStore facts;
    private final ForgetMemory forget;
    private final Embeddings embeddings;
    private final Clocks clocks;
    private final Supplier<UUID> ids;
    private final Map<String, Proposal> pending = new LinkedHashMap<>();
    private final List<MemoryListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ForgetConfirmations(FactStore facts, ForgetMemory forget, Embeddings embeddings, Clocks clocks, Supplier<UUID> ids) {
        this.facts = facts;
        this.forget = forget;
        this.embeddings = embeddings;
        this.clocks = clocks;
        this.ids = ids;
    }

    public void addListener(MemoryListener l) {
        listeners.add(l);
    }

    private Instant now() {
        return EventFeeds.instant(clocks.wallSeconds());
    }

    private void changed() {
        for (MemoryListener l : listeners) {
            try {
                l.onMemory("forget", Map.of("pending", pending().size()));
            } catch (RuntimeException e) {
                log.warning("memory listener failed: " + e);
            }
        }
    }

    private synchronized Proposal add(String query, List<Fact> matches, boolean everything, String origin, long turn) {
        expire();
        Instant now = now();
        String code = ids.get().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        Proposal p = new Proposal(code, query, List.copyOf(matches), everything, origin, turn, now, now.plus(TTL));
        if (!matches.isEmpty() || everything) {
            pending.put(code, p);
        }
        return p;
    }

    private synchronized void expire() {
        Instant now = now();
        pending.values().removeIf(p -> !p.expiresAt().isAfter(now));
    }

    @Override
    public Proposal proposeMatching(String query, String origin, long turn, RecallMemory.Audience audience) {
        String q = query == null ? "" : query.strip();
        Instant now = now();
        Sensitivity max = audience.sensitiveAllowed() ? Sensitivity.SENSITIVE : Sensitivity.PERSONAL;
        Map<UUID, Double> found = new LinkedHashMap<>();
        if (!q.isEmpty()) {
            try {
                for (FactStore.Scored s : facts.nearest(embeddings.embed(q), 10, new FactStore.Filter(now, true, true, max))) {
                    if (s.similarity() >= MATCH_FLOOR) {
                        found.put(s.fact().id(), s.similarity());
                    }
                }
            } catch (Embedder.Unavailable e) {
                // words only
            }
            for (String word : MemoryText.searchTerms(q, 3)) {
                for (Fact f : facts.list(new FactStore.Query(word, "", "", "current", null, null, null, now, 10, 0))) {
                    if (f.sensitivity().ordinal() <= max.ordinal()) {
                        found.merge(f.id(), 0.5 + 0.5 * MemoryText.overlap(MemoryText.words(q), f.embeddingText()), Math::max);
                    }
                }
            }
        }
        List<Fact> matches = new ArrayList<>();
        found.entrySet().stream().sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()).limit(MAX_MATCHES)
                .forEach(e -> facts.get(e.getKey()).ifPresent(matches::add));
        Proposal p = add(q, matches, false, origin, turn);
        if (!matches.isEmpty()) {
            changed();
        }
        return p;
    }

    @Override
    public Proposal proposeFact(UUID id) {
        Fact f = facts.get(id).orElseThrow(() -> new IllegalArgumentException("no such fact"));
        return add(f.statement(), List.of(f), false, "app", -1);
    }

    @Override
    public Proposal proposeEverything() {
        return add("", List.of(), true, "app", -1);
    }

    @Override
    public Outcome confirm(String code, long turn, String phrase) {
        Proposal p;
        synchronized (this) {
            expire();
            p = code == null ? null : pending.get(code.strip().toUpperCase(Locale.ROOT));
            if (p == null) {
                return new Outcome(false, "that confirmation code is unknown or has expired: ask again", null);
            }
            if ("voice".equals(p.origin()) && turn >= 0 && turn <= p.turn()) {
                return new Outcome(false, "the person has not confirmed yet: tell them what would be forgotten and ask first",
                        null);
            }
            if (p.everything() && !EVERYTHING_PHRASE.equalsIgnoreCase(phrase == null ? "" : phrase.strip())) {
                return new Outcome(false, "type \"" + EVERYTHING_PHRASE + "\" to confirm", null);
            }
            pending.remove(p.code());
        }
        ForgetMemory.Forgotten done;
        if (p.everything()) {
            done = forget.forgetEverything();
        } else {
            int events = 0;
            int n = 0;
            int stale = 0;
            Set<UUID> gone = new LinkedHashSet<>();
            for (Fact f : p.facts()) {
                if (gone.contains(f.id())) {
                    continue;
                }
                facts.versions(f.id()).forEach(v -> gone.add(v.id()));
                ForgetMemory.Forgotten one = forget.forgetFact(f.id());
                events += one.events();
                n += one.facts();
                stale += one.staleEpisodes();
            }
            done = new ForgetMemory.Forgotten(events, n, stale);
        }
        log.info("memory: forgot " + done.facts() + " fact(s) after the owner's confirmation (" + p.origin() + ")");
        changed();
        return new Outcome(true, "", done);
    }

    @Override
    public boolean cancel(String code) {
        boolean removed;
        synchronized (this) {
            removed = code != null && pending.remove(code.strip().toUpperCase(Locale.ROOT)) != null;
        }
        if (removed) {
            changed();
        }
        return removed;
    }

    @Override
    public synchronized List<Proposal> pending() {
        expire();
        return pending.values().stream().sorted(Comparator.comparing(Proposal::createdAt).reversed()).toList();
    }
}

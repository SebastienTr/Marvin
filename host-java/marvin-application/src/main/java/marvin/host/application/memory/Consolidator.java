// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Redaction;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.memory.Vectors;
import marvin.host.domain.shared.Clocks;

/**
 * One batch through extraction, reconciliation and provenance (docs/design.md 5.2, steps 1 to 3).
 *
 * <p>A batch is all or nothing: its plans are decided first (each candidate sees the facts of the candidates
 * before it, still in memory), then written in order and the events marked read. A pass that yields to the voice
 * in the middle of a batch writes nothing of it; the next pass does it again.
 *
 * <p>The writes are one transaction, done only if the owner changed nothing meanwhile ({@link MemoryGuard}) and the
 * facts the plans end are still current ({@link FactStore.Conflict}); otherwise nothing is written and the batch
 * stays unread, to be decided again from fresh facts. A sensitive fact learned from a conversation makes its lines
 * sensitive too: they leave the day summaries and what a guest may hear recalled.
 */
public final class Consolidator {
    private static final Logger log = Logger.getLogger("marvin.memory");

    private final EventLog events;
    private final FactStore facts;
    private final ProfileStore profiles;
    private final Embeddings embeddings;
    private final MemoryModel model;
    private final ZoneId zone;
    private final MemoryConfig config;
    private final Clocks clocks;
    private final Supplier<UUID> ids;
    private final EpisodeStore episodes;
    private final MemoryGuard guard;

    public Consolidator(EventLog events, FactStore facts, ProfileStore profiles, Embeddings embeddings, MemoryModel model,
                        ZoneId zone, MemoryConfig config, Clocks clocks, Supplier<UUID> ids, EpisodeStore episodes,
                        MemoryGuard guard) {
        this.episodes = episodes;
        this.guard = guard;
        this.events = events;
        this.facts = facts;
        this.profiles = profiles;
        this.embeddings = embeddings;
        this.model = model;
        this.zone = zone;
        this.config = config;
        this.clocks = clocks;
        this.ids = ids;
    }

    /**
     * What one batch did; {@code operations}: {@code "<OP> [<subject>] <candidate statement>"}, in order.
     * {@code redo}: nothing was written because the owner changed memory meanwhile (the batch stays unread).
     */
    public record Result(int candidates, int added, int updated, int invalidated, int noop, int dropped, boolean skipped,
                         int modelCalls, List<String> operations, boolean redo, MemoryModel.Usage usage) {

        public Result(int candidates, int added, int updated, int invalidated, int noop, int dropped, boolean skipped,
                      int modelCalls, List<String> operations) {
            this(candidates, added, updated, invalidated, noop, dropped, skipped, modelCalls, operations, false,
                    MemoryModel.Usage.NONE);
        }

        void into(Map<String, Integer> counts) {
            counts.merge("prompt_tokens", usage.promptTokens(), Integer::sum);
            counts.merge("model_ms", (int) Math.round(usage.totalSeconds() * 1000), Integer::sum);
            if (redo) {
                counts.merge("redone_batches", 1, Integer::sum);
                return;
            }
            counts.merge("batches", 1, Integer::sum);
            counts.merge("candidates", candidates, Integer::sum);
            counts.merge("added", added, Integer::sum);
            counts.merge("updated", updated, Integer::sum);
            counts.merge("invalidated", invalidated, Integer::sum);
            counts.merge("noop", noop, Integer::sum);
            counts.merge("dropped", dropped, Integer::sum);
            counts.merge("skipped_batches", skipped ? 1 : 0, Integer::sum);
        }
    }

    /**
     * Processes a batch: extraction, then for each candidate the similar current facts and the model's operation,
     * then the writes. Throws {@link MemoryModel.Cancelled} (nothing written) when {@code cancelled} becomes true.
     */
    public Result process(List<MemoryEvent> batch, MemoryModel.Target target, BooleanSupplier cancelled) {
        List<MemoryModel.Line> lines = new ArrayList<>();
        Sensitivity sources = Sensitivity.NORMAL;
        for (MemoryEvent e : batch) {
            lines.add(new MemoryModel.Line(e.ts().atZone(zone), who(e), e.body()));
            sources = sources.atLeast(e.sensitivity());
        }
        long seen = guard.generation();
        MemoryEvent last = batch.getLast();
        List<Long> eventIds = batch.stream().map(MemoryEvent::id).toList();
        String profile = profiles.active(Block.PROFILE).map(BlockVersion::content).orElse("");
        MemoryModel.Extraction extraction;
        int calls = 1;
        try {
            extraction = model.extract(target, new MemoryModel.ExtractRequest(lines, profile, last.ts().atZone(zone)), cancelled);
        } catch (MemoryModel.BadOutput first) {
            calls++;
            try {
                extraction = model.extract(target, new MemoryModel.ExtractRequest(lines, profile, last.ts().atZone(zone)), cancelled);
            } catch (MemoryModel.BadOutput again) {
                log.warning("memory: a batch of " + batch.size() + " events was skipped, the model's output twice unreadable: "
                        + again.getMessage());
                events.markConsolidated(eventIds, now());
                return new Result(0, 0, 0, 0, 0, 0, true, calls, List.of());
            }
        }
        Usage usage = new Usage(extraction.usage());

        List<FactCandidate> accepted = new ArrayList<>();
        int dropped = 0;
        boolean secret = false;
        for (FactCandidate.Raw raw : extraction.facts()) {
            switch (FactCandidate.check(raw, zone, sources)) {
                case FactCandidate.Accepted a -> accepted.add(a.candidate());
                case FactCandidate.Dropped d -> {
                    dropped++;
                    secret |= "secret".equals(d.reason());
                }
            }
        }
        if (secret) {
            for (MemoryEvent e : batch) {
                String redacted = "heard".equals(e.kind()) ? Redaction.redactLikelyCodes(e.body()) : Redaction.redact(e.body());
                if (!redacted.equals(e.body())) {
                    events.redact(e.id(), redacted);
                }
            }
        }
        accepted = dedupe(accepted);

        boolean sensitive = accepted.stream().anyMatch(c -> c.sensitivity().ordinal() >= Sensitivity.SENSITIVE.ordinal());
        // throws Embedder.Unavailable: nothing written, the batch stays unread until the embedding model is back
        List<float[]> vectors = embeddings.embed(accepted.stream().map(c -> Fact.embeddingText(c.subject(), c.statement())).toList());
        Instant now = now();
        Instant eventTime = last.ts();
        String extractedBy = model.version() + " " + target.model();
        List<Pending> pending = new ArrayList<>();
        Set<UUID> ended = new HashSet<>();
        List<Reconciliation.Plan> plans = new ArrayList<>();
        Map<UUID, float[]> vectorOf = new HashMap<>();
        List<String> ops = new ArrayList<>();
        int added = 0;
        int updated = 0;
        int invalidated = 0;
        int noop = 0;
        for (int i = 0; i < accepted.size(); i++) {
            if (cancelled.getAsBoolean()) {
                throw new MemoryModel.Cancelled();
            }
            FactCandidate c = accepted.get(i);
            float[] v = vectors.get(i);
            List<Fact> similar = similar(v, now, pending, ended);
            Operation op;
            if (similar.isEmpty()) {
                op = new Operation.Add();
            } else {
                calls++;
                MemoryModel.Decision d = model.reconcile(target, new MemoryModel.ReconcileRequest(c, similar,
                        eventTime.atZone(zone)), cancelled);
                usage.add(d.usage());
                op = Operation.parse(d.operation(), d.target(), d.statement(), d.validTo(), similar, zone);
            }
            Reconciliation.Plan plan = Reconciliation.plan(c, op, similar, eventIds, eventTime, now, ids, extractedBy);
            for (Fact f : plan.added()) {
                float[] fv = f.statement().equals(c.statement()) ? v : embeddings.embed(f.embeddingText());
                vectorOf.put(f.id(), fv);
                pending.add(new Pending(f, fv));
            }
            for (Reconciliation.Expiry x : plan.expired()) {
                ended.add(x.id());
                pending.removeIf(p -> p.fact().id().equals(x.id()));
            }
            plans.add(plan);
            ops.add(plan.applied().name() + " [" + c.subject() + "] " + c.statement());
            switch (plan.applied()) {
                case Operation.Add a -> added++;
                case Operation.Update u -> updated++;
                case Operation.Invalidate x -> invalidated++;
                case Operation.Noop n -> noop++;
            }
        }
        if (cancelled.getAsBoolean()) {
            throw new MemoryModel.Cancelled();
        }
        Map<UUID, float[]> vs = new LinkedHashMap<>();
        plans.forEach(p -> p.added().forEach(f -> vs.put(f.id(), vectorOf.get(f.id()))));
        boolean written;
        try {
            written = guard.write(seen, () -> {
                facts.apply(plans, vs);
                if (sensitive) {
                    events.relabel(eventIds, Sensitivity.SENSITIVE);
                    // a day already summarised with these lines is written again without them
                    for (MemoryEvent e : batch) {
                        java.time.LocalDate d = e.ts().atZone(zone).toLocalDate();
                        if (episodes.get(marvin.host.domain.memory.EpisodeLevel.DAY, d).isPresent()) {
                            episodes.markStale(d.atStartOfDay(zone).toInstant(), d.plusDays(1).atStartOfDay(zone).toInstant());
                        }
                    }
                }
                events.markConsolidated(eventIds, now);
            });
        } catch (FactStore.Conflict e) {
            log.info("memory: a batch was not written (" + e.getMessage() + "); the next pass does it again");
            written = false;
        }
        if (!written) {
            return new Result(accepted.size() + dropped, 0, 0, 0, 0, dropped, false, calls, ops, true, usage.total());
        }
        return new Result(accepted.size() + dropped, added, updated, invalidated, noop, dropped, false, calls, ops, false,
                usage.total());
    }

    /** The model's counts over a batch's calls. */
    private static final class Usage {
        int prompt;
        double promptS;
        int out;
        double total;

        Usage(MemoryModel.Usage first) {
            add(first);
        }

        void add(MemoryModel.Usage u) {
            if (u != null) {
                prompt += u.promptTokens();
                promptS += u.promptSeconds();
                out += u.outputTokens();
                total += u.totalSeconds();
            }
        }

        MemoryModel.Usage total() {
            return new MemoryModel.Usage(prompt, promptS, out, total);
        }
    }

    private record Pending(Fact fact, float[] vector) {
    }

    /** The most similar current facts: stored ones and this batch's new ones, above the floor, best first. */
    private List<Fact> similar(float[] v, Instant now, List<Pending> pending, Set<UUID> ended) {
        List<FactStore.Scored> all = new ArrayList<>();
        for (FactStore.Scored s : facts.nearest(v, config.similarK(), FactStore.Filter.current(now))) {
            if (!ended.contains(s.fact().id())) {
                all.add(s);
            }
        }
        for (Pending p : pending) {
            all.add(new FactStore.Scored(p.fact(), Vectors.cosine(v, p.vector())));
        }
        return all.stream()
                .filter(s -> s.similarity() >= config.similarityFloor())
                .sorted(Comparator.comparingDouble(FactStore.Scored::similarity).reversed())
                .limit(config.similarK())
                .map(FactStore.Scored::fact)
                .toList();
    }

    /** The same statement twice in one extraction is one candidate. */
    private static List<FactCandidate> dedupe(List<FactCandidate> in) {
        Map<String, FactCandidate> out = new LinkedHashMap<>();
        for (FactCandidate c : in) {
            out.putIfAbsent(c.subject() + "\n" + c.statement().toLowerCase(java.util.Locale.ROOT), c);
        }
        return new ArrayList<>(out.values());
    }

    static String who(MemoryEvent e) {
        if (MemorySources.CONVERSATION.equals(e.source())) {
            return "reply".equals(e.kind()) ? "Marvin" : "Owner";
        }
        return e.source();
    }

    private Instant now() {
        return marvin.host.domain.memory.EventFeeds.instant(clocks.wallSeconds());
    }
}

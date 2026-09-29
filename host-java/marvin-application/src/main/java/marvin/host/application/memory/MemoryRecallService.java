// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.memory.port.in.RecallMemory;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.MemoryListener;
import marvin.host.application.memory.port.out.ModelWarmUp;
import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.memory.MemoryText;
import marvin.host.domain.memory.RecallWindow;
import marvin.host.domain.memory.RetrievalScoring;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.shared.PyNumbers;

/**
 * The read path of memory (docs/design.md 5.3): the profile for the system prompt, the scored candidates of each
 * question's memory sections, and the {@code recall} tool.
 *
 * <p>Automatic retrieval: the question's embedding, the 30 nearest facts that are current, not archived and allowed
 * for the audience (no {@code sensitive} fact while someone else is in the room or for a cloud model), scored by
 * relevance, recency and importance ({@link RetrievalScoring}), those under the relevance floor left out. The
 * context assembler cuts them to the budget; the ones it sends are marked used.
 *
 * <p>When the owner changes the profile in the app, the voice's prompt cache is warmed up again with the new system
 * prompt (the worker does the same after its nightly rewrite).
 */
public final class MemoryRecallService implements RecallMemory, MemoryListener {
    private static final Logger log = Logger.getLogger("marvin.memory");
    /** Facts scored per question (design: the 30 nearest). */
    static final int CANDIDATES = 30;
    /** What {@code recall} returns at most. */
    static final int RECALL_FACTS = 8;
    static final int RECALL_DAYS = 3;
    static final int RECALL_SAID = 5;
    /** A recalled fact must be at least this similar (lower than the automatic floor: the owner asked). */
    static final double RECALL_FLOOR = 0.35;
    static final int TEXT_CHARS = 240;

    private final FactStore facts;
    private final EventLog events;
    private final EpisodeStore episodes;
    private final ProfileStore profiles;
    private final Embeddings embeddings;
    private final LocalDays days;
    private final Clocks clocks;
    private final RetrievalScoring scoring;
    private final ModelWarmUp warmUp;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("memory-used").factory());
    private volatile long profileVersion = -1;

    public MemoryRecallService(FactStore facts, EventLog events, EpisodeStore episodes, ProfileStore profiles,
                               Embeddings embeddings, LocalDays days, Clocks clocks, RetrievalScoring scoring, ModelWarmUp warmUp) {
        this.facts = facts;
        this.events = events;
        this.episodes = episodes;
        this.profiles = profiles;
        this.embeddings = embeddings;
        this.days = days;
        this.clocks = clocks;
        this.scoring = scoring;
        this.warmUp = warmUp;
    }

    private Instant now() {
        return EventFeeds.instant(clocks.wallSeconds());
    }

    private ZoneId zone() {
        return days.zone();
    }

    // ------------------------------------------------------------------ profile

    @Override
    public Optional<BlockVersion> profile() {
        try {
            Optional<BlockVersion> p = profiles.active(Block.PROFILE);
            if (profileVersion < 0) {
                profileVersion = p.map(BlockVersion::id).orElse(0L);
            }
            return p;
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the profile", e);
            return Optional.empty();
        }
    }

    /** The owner changed memory in the app: a profile of another version means a new system prompt, warmed up now. */
    @Override
    public void onMemory(String kind, Map<String, Object> payload) {
        if (!"changed".equals(kind)) {
            return;
        }
        long now = profile().map(BlockVersion::id).orElse(0L);
        long before = profileVersion;
        profileVersion = now;
        if (before >= 0 && before != now) {
            log.info("profile changed (version " + now + "): warming the voice up");
            warmUp.rewarm();
        }
    }

    // ------------------------------------------------------------------ automatic retrieval

    @Override
    public Recollection recollect(String question, Audience audience) {
        Instant now = now();
        List<Line> gist = List.of();
        try {
            gist = gist(question, now);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read today's summaries", e);
        }
        if (question == null || question.isBlank()) {
            return new Recollection(List.of(), gist, 0, 0, "");
        }
        double t0 = clocks.monotonicSeconds();
        float[] q;
        try {
            q = embeddings.embed(question);
        } catch (Embedder.Unavailable e) {
            return new Recollection(List.of(), gist, clocks.monotonicSeconds() - t0, 0, e.getMessage());
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "the question's embedding failed", e);
            return new Recollection(List.of(), gist, clocks.monotonicSeconds() - t0, 0, String.valueOf(e.getMessage()));
        }
        double t1 = clocks.monotonicSeconds();
        try {
            Sensitivity max = audience.sensitiveAllowed() ? Sensitivity.SENSITIVE : Sensitivity.PERSONAL;
            List<FactStore.Scored> near = facts.nearest(q, CANDIDATES, new FactStore.Filter(now, true, false, max));
            List<RetrievalScoring.Scored> scored = scoring.score(near.stream().map(FactStore.Scored::fact).toList(),
                    near.stream().map(FactStore.Scored::similarity).toList(), now);
            List<Line> lines = new ArrayList<>();
            for (RetrievalScoring.Scored s : scored) {
                Fact f = s.fact();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("fact", f.id().toString());
                d.put("similarity", round(s.similarity()));
                d.put("relevance", round(s.relevance()));
                d.put("recency", round(s.recency()));
                d.put("importance", round(s.importance()));
                d.put("sensitivity", f.sensitivity().wire());
                d.put("learned", MemoryText.date(f.learnedAt(), zone()));
                lines.add(new Line(f.id().toString(), MemoryText.contextLine(f, now, zone()), s.score(), d));
            }
            return new Recollection(lines, gist, t1 - t0, clocks.monotonicSeconds() - t1, "");
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "fact retrieval failed", e);
            return new Recollection(List.of(), gist, t1 - t0, clocks.monotonicSeconds() - t1, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Today's and yesterday's summaries, sentence by sentence (docs/design.md 5.3, "today so far"): a sentence scores
     * by its words in common with the question, then today before yesterday, then its place in the summary (a
     * summary leads with what mattered). Sensitive days are never summarised with sensitive events, so a summary is
     * safe for any audience.
     */
    List<Line> gist(String question, Instant now) {
        LocalDate today = now.atZone(zone()).toLocalDate();
        Set<String> q = MemoryText.words(question);
        List<Line> out = new ArrayList<>();
        for (int back = 0; back <= 1; back++) {
            LocalDate day = today.minusDays(back);
            Optional<Episode> e = episodes.get(EpisodeLevel.DAY, day);
            if (e.isEmpty() || e.get().summary().isBlank()) {
                continue;
            }
            String label = back == 0 ? "Today" : "Yesterday";
            List<String> ss = MemoryText.sentences(e.get().summary());
            for (int i = 0; i < ss.size(); i++) {
                double score = 1.0 * MemoryText.overlap(q, ss.get(i)) + (back == 0 ? 0.3 : 0.2) + 0.1 * (1 - (double) i / ss.size());
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("day", day.toString());
                out.add(new Line("episode:" + day + ":" + i, label + ": " + ss.get(i), score, d));
            }
        }
        return out;
    }

    @Override
    public void used(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<UUID> copy = List.copyOf(ids);
        Instant at = now();
        writer.execute(() -> {
            try {
                facts.touch(copy, at);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "could not mark facts used", e);
            }
        });
    }

    /** Waits for the "used" marks queued so far (tests). */
    void drain() {
        try {
            writer.submit(() -> { }).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void close() {
        writer.shutdown();
    }

    // ------------------------------------------------------------------ the recall tool

    @Override
    public Map<String, Object> recall(String query, String period, Audience audience) {
        Instant now = now();
        RecallWindow w = RecallWindow.of(period, now, zone());
        String qtext = query == null ? "" : query.strip();
        Set<String> qwords = MemoryText.words(qtext);
        Sensitivity max = audience.sensitiveAllowed() ? Sensitivity.SENSITIVE : Sensitivity.PERSONAL;

        // facts: nearest by meaning (current, past and archived), then words in common
        Map<UUID, Double> found = new LinkedHashMap<>();
        String problem = "";
        if (!qtext.isEmpty()) {
            try {
                float[] v = embeddings.embed(qtext);
                for (FactStore.Scored s : facts.nearest(v, 20, new FactStore.Filter(now, false, true, max))) {
                    if (s.similarity() >= RECALL_FLOOR) {
                        found.put(s.fact().id(), s.similarity());
                    }
                }
            } catch (Embedder.Unavailable e) {
                problem = "searching by meaning is unavailable (" + e.getMessage() + "); searched by words only";
            }
            for (String word : MemoryText.searchTerms(qtext, 3)) {
                for (Fact f : facts.list(new FactStore.Query(word, "", "", "all", null, null, null, now, 20, 0))) {
                    if (f.sensitivity().ordinal() <= max.ordinal()) {
                        found.merge(f.id(), 0.3 + 0.1 * MemoryText.overlap(qwords, f.embeddingText()), Math::max);
                    }
                }
            }
        }
        List<Fact> hits = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : found.entrySet()) {
            facts.get(e.getKey()).ifPresent(f -> {
                if (f.supersededBy() == null && w.overlaps(f.validFrom() != null ? f.validFrom() : f.learnedAt(),
                        f.validTo() != null ? f.validTo() : f.expiredAt(), zone())) {
                    hits.add(f);
                }
            });
        }
        hits.sort(Comparator.comparingDouble((Fact f) -> -found.get(f.id())).thenComparing(Fact::learnedAt, Comparator.reverseOrder()));
        List<Fact> top = hits.subList(0, Math.min(RECALL_FACTS, hits.size()));
        Map<Long, MemoryEvent> sources = new LinkedHashMap<>();
        List<Long> firstSources = top.stream().filter(f -> !f.sources().isEmpty()).map(f -> f.sources().getFirst()).toList();
        if (!firstSources.isEmpty()) {
            events.byIds(firstSources).forEach(e -> sources.put(e.id(), e));
        }
        List<Map<String, Object>> factOut = new ArrayList<>();
        for (Fact f : top) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("statement", f.statement());
            m.put("status", MemoryText.status(f, now));
            m.put("when", MemoryText.validity(f, zone()));
            MemoryEvent src = f.sources().isEmpty() ? null : sources.get(f.sources().getFirst());
            m.put("source", source(f, src));
            factOut.add(m);
        }
        used(top.stream().filter(f -> f.current(now)).map(Fact::id).toList());

        // summaries: the window's days (or, without a window, the days whose summary shares words with the query)
        List<Map<String, Object>> dayOut = new ArrayList<>();
        LocalDate today = now.atZone(zone()).toLocalDate();
        LocalDate from = w.bounded() ? w.from() : today.minusDays(90);
        LocalDate to = w.bounded() ? w.to() : today.plusDays(1);
        List<Episode> eps = new ArrayList<>(episodes.list(EpisodeLevel.DAY, from, to));
        if (w.bounded() && eps.isEmpty()) {
            eps.addAll(episodes.list(EpisodeLevel.WEEK, from.minusDays(6), to));
        }
        eps.sort(Comparator.comparingDouble((Episode e) -> -MemoryText.overlap(qwords, e.summary()))
                .thenComparing(Episode::day, Comparator.reverseOrder()));
        for (Episode e : eps) {
            if (dayOut.size() >= RECALL_DAYS) {
                break;
            }
            if (!w.bounded() && MemoryText.overlap(qwords, e.summary()) == 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(e.level() == EpisodeLevel.DAY ? "day" : "week of", MemoryText.date(e.day()));
            m.put("summary", shorten(e.summary(), 2 * TEXT_CHARS));
            dayOut.add(m);
        }

        // what was said: conversation lines with the query's words, in the window
        List<Map<String, Object>> saidOut = new ArrayList<>();
        if (!qwords.isEmpty()) {
            Map<Long, MemoryEvent> said = new LinkedHashMap<>();
            for (String word : MemoryText.searchTerms(qtext, 3)) {
                for (MemoryEvent e : events.recent(word, 0, 30)) {
                    if (MemorySources.CONVERSATION.equals(e.source()) && w.contains(e.ts(), zone())
                            && (e.sensitivity() != Sensitivity.SENSITIVE || audience.sensitiveAllowed())) {
                        said.putIfAbsent(e.id(), e);
                    }
                }
            }
            said.values().stream()
                    .sorted(Comparator.comparingDouble((MemoryEvent e) -> -MemoryText.overlap(qwords, e.body()))
                            .thenComparing(MemoryEvent::ts, Comparator.reverseOrder()))
                    .limit(RECALL_SAID)
                    .forEach(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", MemoryText.date(e.ts(), zone()));
                        m.put("who", "heard".equals(e.kind()) ? "the person" : "you");
                        m.put("text", shorten(e.body(), TEXT_CHARS));
                        saidOut.add(m);
                    });
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", qtext);
        out.put("period", w.period());
        out.put("facts", factOut);
        out.put("days", dayOut);
        out.put("said", saidOut);
        if (factOut.isEmpty() && dayOut.isEmpty() && saidOut.isEmpty()) {
            out.put("note", "nothing in memory matches: say you do not remember");
        }
        if (!problem.isEmpty()) {
            out.put("problem", problem);
        }
        return out;
    }

    private String source(Fact f, MemoryEvent src) {
        String who = switch (f.origin()) {
            case OWNER -> "the owner told you";
            case TASK -> "a task found it";
            default -> "learned from a conversation";
        };
        if (src == null) {
            return who;
        }
        return who + " on " + MemoryText.date(src.ts(), zone());
    }

    private static String shorten(String s, int n) {
        String t = s == null ? "" : s.strip();
        return t.length() <= n ? t : t.substring(0, n - 1).stripTrailing() + "…";
    }

    private static double round(double x) {
        return PyNumbers.round(x, 3);
    }
}

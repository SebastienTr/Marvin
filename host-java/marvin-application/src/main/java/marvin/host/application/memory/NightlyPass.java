// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.port.out.MemoryStateStore;
import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.memory.ProfileText;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * The nightly steps after extraction (docs/design.md 5.2, steps 4 to 7): day episodes and their week and month
 * roll-ups, the profile rewrite, decay, retention. Each step is idempotent: a pass cut short by the voice is
 * resumed by the next one. Every write goes through the {@link MemoryGuard}: a summary or a profile decided before
 * the owner forgot or changed something is not written (the next pass decides it again).
 *
 * <p>A period whose summary the model cannot write (its output unreadable twice) is recorded in the state and tried
 * again one, then two, then three nights later; the other periods and the other steps go on meanwhile.
 */
public final class NightlyPass {
    private static final Logger log = Logger.getLogger("marvin.memory");
    /** A day's events given to the summary at most (characters), about 3500 tokens. */
    static final int DAY_INPUT_CHARS = 12_000;
    static final int EVENT_CHARS = 300;
    static final int ROLLUPS_PER_NIGHT = 8;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.ROOT);

    private final EventLog events;
    private final FactStore facts;
    private final EpisodeStore episodes;
    private final ProfileStore profiles;
    private final Embeddings embeddings;
    private final MemoryModel model;
    private final LocalDays days;
    private final MemoryConfig config;
    private final MemorySettingsService settings;
    private final Clocks clocks;
    private final MemoryGuard guard;
    private final MemoryStateStore state;
    /** A period whose summary failed is tried again after this many nights, then gives up. */
    static final int MAX_TRIES = 3;

    public NightlyPass(EventLog events, FactStore facts, EpisodeStore episodes, ProfileStore profiles, Embeddings embeddings,
                       MemoryModel model, LocalDays days, MemoryConfig config, MemorySettingsService settings, Clocks clocks,
                       MemoryGuard guard, MemoryStateStore state) {
        this.events = events;
        this.facts = facts;
        this.episodes = episodes;
        this.profiles = profiles;
        this.embeddings = embeddings;
        this.model = model;
        this.days = days;
        this.config = config;
        this.settings = settings;
        this.clocks = clocks;
        this.guard = guard;
        this.state = state;
    }

    Instant now() {
        return EventFeeds.instant(clocks.wallSeconds());
    }

    LocalDate today() {
        return now().atZone(days.zone()).toLocalDate();
    }

    private Instant startOf(LocalDate d) {
        return d.atStartOfDay(days.zone()).toInstant();
    }

    // ------------------------------------------------------------------ embeddings

    /** Drops every fact's embedding, for {@link #embedMissing} to redo them with the current model; returns how many. */
    int clearEmbeddings() {
        return facts.clearEmbeddings();
    }

    /** Embeds the facts written while the embedding model was missing; returns how many. */
    int embedMissing(BooleanSupplier cancelled) {
        int n = 0;
        while (!cancelled.getAsBoolean()) {
            List<Fact> missing = facts.withoutEmbedding(50);
            if (missing.isEmpty()) {
                break;
            }
            List<float[]> v = embeddings.embed(missing.stream().map(Fact::embeddingText).toList());
            for (int i = 0; i < missing.size(); i++) {
                facts.setEmbedding(missing.get(i).id(), v.get(i));
            }
            n += missing.size();
        }
        return n;
    }

    // ------------------------------------------------------------------ episodes

    /**
     * Writes the missing and stale day episodes up to yesterday (at most {@code maxDaysPerNight} summaries); returns
     * how many. The days to do come from the log (the days that have events), so a long gap without events does not
     * stall the episodes; a stale day with nothing left to summarise loses its episode.
     */
    int dayEpisodes(MemoryModel.Target target, BooleanSupplier cancelled) {
        LocalDate today = today();
        Set<LocalDate> todo = new LinkedHashSet<>();
        for (Episode e : episodes.stale()) {
            if (e.level() == EpisodeLevel.DAY && e.day().isBefore(today)) {
                todo.add(e.day());
            }
        }
        todo.addAll(retriesDue(EpisodeLevel.DAY, today));
        Optional<LocalDate> through = through();
        LocalDate from = episodes.latest(EpisodeLevel.DAY).map(e -> e.day().plusDays(1)).orElse(null);
        if (through.isPresent() && (from == null || !through.get().isBefore(from))) {
            from = through.get().plusDays(1);
        }
        Instant scanFrom = from == null ? Instant.EPOCH : startOf(from);
        List<LocalDate> ahead = events.days(scanFrom, days.zone(), config.maxDaysPerNight() * 4).stream()
                .filter(d -> d.isBefore(today)).toList();
        todo.addAll(ahead);
        int written = 0;
        LocalDate lastNew = null;           // the cursor moves over the new days done, in order
        boolean inOrder = true;
        for (LocalDate d : todo.stream().sorted().toList()) {
            if (written >= config.maxDaysPerNight() || cancelled.getAsBoolean()) {
                break;
            }
            long seen = guard.generation();
            List<MemoryEvent> evs = events.between(startOf(d), startOf(d.plusDays(1)), 5000);
            List<String> items = dayItems(evs, days.zone());
            boolean done;
            if (items.isEmpty()) {
                done = guard.write(seen, () -> {
                    if (episodes.get(EpisodeLevel.DAY, d).isPresent()) {
                        episodes.markStale(startOf(d), startOf(d.plusDays(1)));      // its week and month too
                        episodes.delete(EpisodeLevel.DAY, d);
                    }
                });
            } else {
                MemoryModel.Text t;
                try {
                    t = model.summarize(target, new MemoryModel.SummaryRequest(EpisodeLevel.DAY, d, d, items,
                            EpisodeLevel.DAY.maxWords()), cancelled);
                } catch (MemoryModel.BadOutput e) {
                    failed(EpisodeLevel.DAY, d, today, e);
                    done = true;
                    if (ahead.contains(d) && inOrder) {
                        lastNew = d;
                    }
                    continue;
                }
                Episode ep = new Episode(0, EpisodeLevel.DAY, d, startOf(d), startOf(d.plusDays(1)), t.text().strip(), false, now(),
                        evs.size());
                done = write(seen, ep);
                if (done) {
                    written++;
                    succeeded(EpisodeLevel.DAY, d);
                }
            }
            if (ahead.contains(d)) {
                if (done && inOrder) {
                    lastNew = d;
                } else {
                    inOrder = false;
                }
            }
        }
        if (lastNew != null) {
            setThrough(lastNew);
        }
        return written;
    }

    // ------------------------------------------------------------------ progress and failures (memory.state "nightly")

    private Optional<LocalDate> through() {
        Object o = state.get("nightly").get("days_through");
        try {
            return o == null ? Optional.empty() : Optional.of(LocalDate.parse(String.valueOf(o)));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private void setThrough(LocalDate d) {
        java.util.Map<String, Object> n = state.get("nightly");
        n.put("days_through", d.toString());
        state.put("nightly", n);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> failures() {
        Object o = state.get("nightly").get("failed");
        return o instanceof java.util.Map<?, ?> m ? new java.util.LinkedHashMap<>((java.util.Map<String, Object>) m)
                : new java.util.LinkedHashMap<>();
    }

    private void putFailures(java.util.Map<String, Object> f) {
        java.util.Map<String, Object> n = state.get("nightly");
        n.put("failed", f);
        state.put("nightly", n);
    }

    private static String key(EpisodeLevel level, LocalDate d) {
        return level.wire() + " " + d;
    }

    /** Periods of a level whose failed summary is due to be tried again. */
    private List<LocalDate> retriesDue(EpisodeLevel level, LocalDate today) {
        List<LocalDate> out = new ArrayList<>();
        failures().forEach((k, v) -> {
            if (k.startsWith(level.wire() + " ") && v instanceof java.util.Map<?, ?> m) {
                Object after = m.get("after");
                if (after != null && !LocalDate.parse(String.valueOf(after)).isAfter(today)) {
                    out.add(LocalDate.parse(k.substring(level.wire().length() + 1)));
                }
            }
        });
        return out;
    }

    private void failed(EpisodeLevel level, LocalDate d, LocalDate today, RuntimeException e) {
        java.util.Map<String, Object> f = failures();
        Object prev = f.get(key(level, d));
        int tries = (prev instanceof java.util.Map<?, ?> m && m.get("tries") instanceof Number n ? n.intValue() : 0) + 1;
        java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("tries", tries);
        entry.put("error", String.valueOf(e.getMessage()));
        if (tries < MAX_TRIES) {
            entry.put("after", today.plusDays(tries).toString());
        }
        f.put(key(level, d), entry);
        putFailures(f);
        log.warning("memory: the " + level.wire() + " summary of " + d + " failed (" + tries + "/" + MAX_TRIES + "), "
                + (tries < MAX_TRIES ? "tried again in " + tries + " night(s)" : "given up") + ": " + e.getMessage());
    }

    private void succeeded(EpisodeLevel level, LocalDate d) {
        java.util.Map<String, Object> f = failures();
        if (f.remove(key(level, d)) != null) {
            putFailures(f);
        }
    }

    /** The owner forgot everything: the progress goes too. */
    void reset() {
        state.put("nightly", java.util.Map.of());
    }

    /** A day's events as lines for its summary: sensitive ones left out, long ones cut, the whole bounded. */
    static List<String> dayItems(List<MemoryEvent> evs, java.time.ZoneId zone) {
        List<String> out = new ArrayList<>();
        int chars = 0;
        String lastBrain = null;
        for (MemoryEvent e : evs) {
            if (e.sensitivity().ordinal() >= Sensitivity.SENSITIVE.ordinal() || e.body().isBlank()) {
                continue;
            }
            if (MemorySources.OWNER.equals(e.source())) {
                continue;
            }
            String body = e.body().length() > EVENT_CHARS ? e.body().substring(0, EVENT_CHARS) + "…" : e.body();
            String who = Consolidator.who(e);
            if (MemorySources.BRAIN.equals(e.source())) {
                if (body.equals(lastBrain)) {
                    continue;
                }
                lastBrain = body;
                who = "Presence";
            }
            String line = HM.format(e.ts().atZone(zone)) + " " + who + ": " + body;
            if (chars + line.length() > DAY_INPUT_CHARS) {
                out.add("(" + (evs.size() - out.size()) + " more events not shown)");
                break;
            }
            out.add(line);
            chars += line.length();
        }
        return out;
    }

    /** Week and month summaries of complete periods that have none (or a stale one); returns how many. */
    int rollUps(MemoryModel.Target target, BooleanSupplier cancelled) {
        int written = 0;
        LocalDate today = today();
        for (EpisodeLevel level : List.of(EpisodeLevel.WEEK, EpisodeLevel.MONTH)) {
            Optional<Episode> firstDay = episodes.list(EpisodeLevel.DAY, LocalDate.of(1970, 1, 1), today).stream().findFirst();
            if (firstDay.isEmpty()) {
                return written;
            }
            Set<LocalDate> staleStarts = new LinkedHashSet<>();
            for (Episode e : episodes.stale()) {
                if (e.level() == level) {
                    staleStarts.add(e.day());
                }
            }
            Set<String> gaveUp = new java.util.HashSet<>();
            failures().forEach((k, v) -> {
                if (v instanceof java.util.Map<?, ?> m && (m.get("after") == null
                        || LocalDate.parse(String.valueOf(m.get("after"))).isAfter(today))) {
                    gaveUp.add(k);
                }
            });
            for (LocalDate s = level.start(firstDay.get().day()); !level.end(s).isAfter(today); s = level.end(s)) {
                if (written >= ROLLUPS_PER_NIGHT || cancelled.getAsBoolean()) {
                    return written;
                }
                Optional<Episode> existing = episodes.get(level, s);
                if (existing.isPresent() && !staleStarts.contains(s) || gaveUp.contains(key(level, s))) {
                    continue;
                }
                long seen = guard.generation();
                LocalDate end = level.end(s);
                // blank (stale) parts are being rewritten: never summarised from what they said before
                List<Episode> parts = (level == EpisodeLevel.WEEK ? episodes.list(EpisodeLevel.DAY, s, end)
                        : episodes.list(EpisodeLevel.WEEK, s, end)).stream().filter(p -> !p.stale() && !p.summary().isBlank()).toList();
                if (parts.isEmpty()) {
                    if (existing.isPresent()) {
                        LocalDate period = s;
                        guard.write(seen, () -> episodes.delete(level, period));
                    }
                    continue;
                }
                List<String> items = parts.stream().map(p -> DAY.format(p.day()) + ": " + p.summary()).toList();
                MemoryModel.Text t;
                try {
                    t = model.summarize(target, new MemoryModel.SummaryRequest(level, s, end.minusDays(1), items,
                            level.maxWords()), cancelled);
                } catch (MemoryModel.BadOutput e) {
                    failed(level, s, today, e);
                    continue;
                }
                if (write(seen, new Episode(0, level, s, startOf(s), startOf(end), t.text().strip(), false, now(),
                        parts.stream().mapToInt(Episode::events).sum()))) {
                    succeeded(level, s);
                    written++;
                }
            }
        }
        return written;
    }

    /** Writes an episode decided at generation {@code seen}; {@code false} when the owner changed memory meanwhile. */
    private boolean write(long seen, Episode e) {
        float[] v = null;
        try {
            v = e.summary().isEmpty() ? null : embeddings.embed(e.summary());
        } catch (Embedder.Unavailable ex) {
            // the summary is kept without its embedding; recall still finds it by text
        }
        float[] vector = v;
        return guard.write(seen, () -> episodes.put(e, vector));
    }

    // ------------------------------------------------------------------ profile

    /**
     * Rewrites the profile from the previous version, the facts learned and ended since, the statements the owner
     * forgot or withdrew since (every line stating them must go, even reworded), and the owner's kept lines (at most
     * {@link Block#maxTokens()}); nothing when nothing changed. Sensitive facts never enter the profile: it is in every
     * prompt, whoever is in the room. An unreadable answer keeps the previous version. Returns whether a new version
     * was written.
     */
    boolean profile(MemoryModel.Target target, BooleanSupplier cancelled) {
        long seen = guard.generation();
        Optional<BlockVersion> active = profiles.active(Block.PROFILE);
        Instant since = active.map(BlockVersion::createdAt).orElse(Instant.EPOCH);
        List<Fact> learned = facts.learnedSince(since).stream()
                .filter(f -> f.sensitivity().ordinal() < Sensitivity.SENSITIVE.ordinal() && !f.archived())
                .sorted(Comparator.comparingInt(Fact::importance).reversed())
                .limit(60)
                .toList();
        List<Fact> ended = facts.endedSince(since).stream()
                .filter(f -> f.sensitivity().ordinal() < Sensitivity.SENSITIVE.ordinal())
                .limit(60)
                .toList();
        List<String> remove = ProfileRemovals.pending(state);
        if (learned.isEmpty() && ended.isEmpty() && remove.isEmpty()) {
            return false;
        }
        String previous = active.map(BlockVersion::content).orElse("");
        List<String> kept = active.map(BlockVersion::keptLines).orElse(List.of());
        MemoryModel.Text t;
        try {
            t = model.rewriteProfile(target, new MemoryModel.ProfileRequest(previous, kept,
                    learned.stream().map(NightlyPass::line).toList(), ended.stream().map(NightlyPass::line).toList(), remove,
                    Block.PROFILE.maxTokens()), cancelled);
        } catch (MemoryModel.BadOutput e) {
            log.warning("memory: the profile rewrite was unreadable, the previous version is kept: " + e.getMessage());
            return false;
        }
        String text = t.text();
        for (String r : remove) {
            text = ProfileText.withoutLinesLike(text, r);      // in case the model kept one word for word
        }
        ProfileText.Enforced e = ProfileText.enforce(text, kept, Block.PROFILE.maxTokens(), config.tokens());
        List<Long> evidence = new ArrayList<>(new LinkedHashSet<>(
                java.util.stream.Stream.concat(learned.stream(), ended.stream()).flatMap(f -> f.sources().stream()).toList()));
        if (evidence.size() > 500) {
            evidence = evidence.subList(evidence.size() - 500, evidence.size());
        }
        List<Long> ev = evidence;
        Instant now = now();
        return guard.write(seen, () -> {
            profiles.add(new BlockVersion(0, Block.PROFILE, e.text(), e.tokens(), BlockVersion.Status.ACTIVE,
                    "nightly rewrite: " + learned.size() + " learned, " + ended.size() + " no longer true"
                            + (remove.isEmpty() ? "" : ", " + remove.size() + " forgotten removed")
                            + (e.dropped().isEmpty() ? "" : ", " + e.dropped().size() + " lines cut to fit"),
                    ev, BlockVersion.Author.WORKER, now, now, kept));
            if (!remove.isEmpty()) {
                // the lines the model took out for a forgotten statement (reworded ones included) leave the history too
                ProfileRemovals.purgeHistory(profiles, config.tokens(), ProfileRemovals.removedFor(previous, e.text(), remove));
                ProfileRemovals.clear(state);
            }
        });
    }

    /** Whether the owner forgot something the profile may still say (the idle pass then rewrites it too). */
    boolean profilePending() {
        return !ProfileRemovals.pending(state).isEmpty();
    }

    private static String line(Fact f) {
        return ("owner".equals(f.subject()) ? "" : "(" + f.subject() + ") ") + f.statement();
    }

    // ------------------------------------------------------------------ decay and retention

    /** Archives the facts that faded; returns how many. Nothing is deleted. */
    int decay() {
        Instant now = now();
        List<UUID> fade = facts.decayable(now).stream().filter(f -> config.decay().archives(f, now)).map(Fact::id).toList();
        if (!fade.isEmpty()) {
            facts.setArchived(fade, true);
        }
        return fade.size();
    }

    /**
     * Deletes brain events older than the retention setting whose day is summarised and which no fact comes from;
     * the conversation is kept until the owner says otherwise. Returns how many.
     */
    int retention() {
        Optional<Episode> lastDay = episodes.latest(EpisodeLevel.DAY);
        if (lastDay.isEmpty()) {
            return 0;
        }
        Instant cutoff = now().minus(Duration.ofDays(settings.settings().retentionDays()));
        Instant summarised = lastDay.get().periodEnd();
        Instant before = cutoff.isBefore(summarised) ? cutoff : summarised;
        return events.deleteUnreferenced(MemorySources.BRAIN, before);
    }

    /** Facts whose events were all forgotten go too. */
    int orphans() {
        return facts.deleteOrphans();
    }

}

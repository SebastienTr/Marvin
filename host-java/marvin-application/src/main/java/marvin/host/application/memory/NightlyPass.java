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
 * resumed by the next one.
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

    public NightlyPass(EventLog events, FactStore facts, EpisodeStore episodes, ProfileStore profiles, Embeddings embeddings,
                       MemoryModel model, LocalDays days, MemoryConfig config, MemorySettingsService settings, Clocks clocks) {
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

    /** Writes the missing and stale day episodes up to yesterday (at most {@code maxDaysPerNight}); returns how many. */
    int dayEpisodes(MemoryModel.Target target, BooleanSupplier cancelled) {
        LocalDate today = today();
        Set<LocalDate> todo = new LinkedHashSet<>();
        for (Episode e : episodes.stale()) {
            if (e.level() == EpisodeLevel.DAY && e.day().isBefore(today)) {
                todo.add(e.day());
            }
        }
        Optional<Instant> first = events.first();
        if (first.isPresent()) {
            LocalDate from = episodes.latest(EpisodeLevel.DAY).map(e -> e.day().plusDays(1))
                    .orElse(first.get().atZone(days.zone()).toLocalDate());
            for (LocalDate d = from; d.isBefore(today) && todo.size() < config.maxDaysPerNight() * 4; d = d.plusDays(1)) {
                todo.add(d);
            }
        }
        int written = 0;
        for (LocalDate d : todo.stream().sorted().toList()) {
            if (written >= config.maxDaysPerNight() || cancelled.getAsBoolean()) {
                break;
            }
            List<MemoryEvent> evs = events.between(startOf(d), startOf(d.plusDays(1)), 5000);
            List<String> items = dayItems(evs, days.zone());
            if (items.isEmpty()) {
                continue;
            }
            MemoryModel.Text t = model.summarize(target, new MemoryModel.SummaryRequest(EpisodeLevel.DAY, d, d, items,
                    EpisodeLevel.DAY.maxWords()), cancelled);
            put(new Episode(0, EpisodeLevel.DAY, d, startOf(d), startOf(d.plusDays(1)), t.text().strip(), false, now(),
                    evs.size()));
            written++;
        }
        return written;
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
            for (LocalDate s = level.start(firstDay.get().day()); !level.end(s).isAfter(today); s = level.end(s)) {
                if (written >= ROLLUPS_PER_NIGHT || cancelled.getAsBoolean()) {
                    return written;
                }
                Optional<Episode> existing = episodes.get(level, s);
                if (existing.isPresent() && !staleStarts.contains(s)) {
                    continue;
                }
                LocalDate end = level.end(s);
                List<Episode> parts = level == EpisodeLevel.WEEK ? episodes.list(EpisodeLevel.DAY, s, end)
                        : episodes.list(EpisodeLevel.WEEK, s, end);
                if (parts.isEmpty()) {
                    continue;
                }
                List<String> items = parts.stream().map(p -> DAY.format(p.day()) + ": " + p.summary()).toList();
                MemoryModel.Text t = model.summarize(target, new MemoryModel.SummaryRequest(level, s, end.minusDays(1), items,
                        level.maxWords()), cancelled);
                put(new Episode(0, level, s, startOf(s), startOf(end), t.text().strip(), false, now(),
                        parts.stream().mapToInt(Episode::events).sum()));
                written++;
            }
        }
        return written;
    }

    private void put(Episode e) {
        float[] v = null;
        try {
            v = e.summary().isEmpty() ? null : embeddings.embed(e.summary());
        } catch (Embedder.Unavailable ex) {
            // the summary is kept without its embedding; recall still finds it by text
        }
        episodes.put(e, v);
    }

    // ------------------------------------------------------------------ profile

    /**
     * Rewrites the profile from the previous version, the facts learned and ended since, and the owner's kept lines
     * (at most {@link Block#maxTokens()}); nothing when nothing changed. Sensitive facts never enter the profile: it is
     * in every prompt, whoever is in the room. Returns whether a new version was written.
     */
    boolean profile(MemoryModel.Target target, BooleanSupplier cancelled) {
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
        if (learned.isEmpty() && ended.isEmpty()) {
            return false;
        }
        List<String> kept = active.map(BlockVersion::keptLines).orElse(List.of());
        MemoryModel.Text t = model.rewriteProfile(target, new MemoryModel.ProfileRequest(
                active.map(BlockVersion::content).orElse(""), kept,
                learned.stream().map(NightlyPass::line).toList(), ended.stream().map(NightlyPass::line).toList(),
                Block.PROFILE.maxTokens()), cancelled);
        ProfileText.Enforced e = ProfileText.enforce(t.text(), kept, Block.PROFILE.maxTokens(), config.tokens());
        List<Long> evidence = new ArrayList<>(new LinkedHashSet<>(
                java.util.stream.Stream.concat(learned.stream(), ended.stream()).flatMap(f -> f.sources().stream()).toList()));
        if (evidence.size() > 500) {
            evidence = evidence.subList(evidence.size() - 500, evidence.size());
        }
        Instant now = now();
        profiles.add(new BlockVersion(0, Block.PROFILE, e.text(), e.tokens(), BlockVersion.Status.ACTIVE,
                "nightly rewrite: " + learned.size() + " learned, " + ended.size() + " no longer true"
                        + (e.dropped().isEmpty() ? "" : ", " + e.dropped().size() + " lines cut to fit"),
                evidence, BlockVersion.Author.WORKER, now, now, kept));
        return true;
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

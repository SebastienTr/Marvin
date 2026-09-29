// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Sensitivity;

/** The append-only event log (docs/design.md 5.4, {@code event_log}). Deletions are real: forgetting. */
public interface EventLog {

    /**
     * Appends events in one transaction; one whose {@code (source, externalRef)} is already there is skipped, so
     * feeding the same record twice (live and backfill) keeps it once. Returns how many were added.
     */
    int append(List<MemoryEvent> drafts);

    /** Appends one event and returns it stored, or empty when it was already there. */
    Optional<MemoryEvent> appendOne(MemoryEvent draft);

    /** Events no pass has read yet, oldest first. */
    List<MemoryEvent> unconsolidated(int limit);

    /** How many events no pass has read yet. */
    long unconsolidatedCount();

    void markConsolidated(Collection<Long> ids, Instant at);

    /** Events with {@code from <= ts < to}, oldest first, at most {@code limit}; withheld events are left out. */
    List<MemoryEvent> between(Instant from, Instant to, int limit);

    /** The events with these ids, oldest first; withheld events are left out. */
    List<MemoryEvent> byIds(Collection<Long> ids);

    /**
     * Newest first, only ids below {@code beforeId} (0: from the newest); {@code query} filters bodies (blank: all).
     * Withheld events are left out.
     */
    List<MemoryEvent> recent(String query, long beforeId, int limit);

    /** The earliest event's time, if any. */
    Optional<Instant> first();

    /** Replaces the body of an event (a secret found in it). */
    void redact(long id, String body);

    /**
     * Raises the sensitivity of events to at least {@code s} (a sensitive fact was learned from them): they then
     * leave day summaries and what a guest may hear recalled.
     */
    void relabel(Collection<Long> ids, Sensitivity s);

    /**
     * Withholds events (the source of a forgotten fact): they stay, so that other facts keep their link, but they
     * are never summarised, recalled, listed or exported again. Returns how many were not withheld yet.
     */
    int withhold(Collection<Long> ids);

    /** The distinct local days (in {@code zone}) with events at or after {@code from}, oldest first, at most {@code limit}. */
    List<LocalDate> days(Instant from, ZoneId zone, int limit);

    /** Deletes events; the facts' links to them go too. Returns how many were deleted. */
    int delete(Collection<Long> ids);

    /** Deletes the events with {@code from <= ts < to}; returns how many. */
    int deleteBetween(Instant from, Instant to);

    /**
     * Deletes events of {@code source} older than {@code before} that are no fact's source; returns how many
     * (retention of raw sensor events once their day is summarised).
     */
    int deleteUnreferenced(String source, Instant before);

    long count();

    /** Everything but the withheld events, oldest first, in pages (export). */
    List<MemoryEvent> page(long afterId, int limit);

    /** Deletes the whole log. */
    void deleteAll();
}

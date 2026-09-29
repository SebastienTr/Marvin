// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import marvin.host.domain.memory.MemoryEvent;

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

    /** Events with {@code from <= ts < to}, oldest first, at most {@code limit}. */
    List<MemoryEvent> between(Instant from, Instant to, int limit);

    List<MemoryEvent> byIds(Collection<Long> ids);

    /** Newest first, only ids below {@code beforeId} (0: from the newest); {@code query} filters bodies (blank: all). */
    List<MemoryEvent> recent(String query, long beforeId, int limit);

    /** The earliest event's time, if any. */
    Optional<Instant> first();

    /** Replaces the body of an event (a secret found in it). */
    void redact(long id, String body);

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

    /** Everything, oldest first, in pages (export). */
    List<MemoryEvent> page(long afterId, int limit);

    /** Deletes the whole log. */
    void deleteAll();
}

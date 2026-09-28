// SPDX-License-Identifier: MIT
package marvin.host.application.presence.port.out;

import java.util.Collection;
import java.util.List;

import marvin.host.domain.presence.history.Sample;
import marvin.host.domain.presence.history.StoredEvent;

/** Where the presence history is kept: stored events and per-minute samples. */
public interface PresenceHistoryStore {

    /**
     * Stores an event; returns it with its id.
     *
     * @param deviceTUs the robot's clock for a brain event, kept for reference; {@code null} for the host's own
     */
    StoredEvent add(StoredEvent event, Long deviceTUs);

    /** Events with {@code start <= ts < end}, oldest first. */
    List<StoredEvent> events(double start, double end);

    /** The latest events, newest first, only those with {@code id > sinceId}, without the given kinds. */
    List<StoredEvent> recent(int limit, long sinceId, Collection<String> excludeKinds);

    /** Stores a sample (the same minute again replaces it). */
    void addSample(Sample sample);

    /** Samples with {@code start <= ts < end}, oldest first. */
    List<Sample> samples(double start, double end);
}

// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.time.Instant;
import java.util.List;

import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactTimeline;
import marvin.host.domain.memory.MemoryGraph;

/**
 * Four read-only pictures of memory for the app's "See your memory" (docs/memory.md, "Seeing memory"): who and what
 * facts are about (a graph), what they mean (the embeddings projected to two dimensions), when they held (a
 * timeline), and how memory is built (the flow from the log to the profile). Forgotten facts are gone from the stores,
 * so they never appear; sensitive facts are marked by their {@link Fact#sensitivity()}.
 */
public interface VisualiseMemory {

    /** The graph and the facts its edges and nodes stand for (each fact in its latest wording). */
    record GraphView(MemoryGraph.Graph graph, List<Fact> facts) {
    }

    /** A fact placed by its meaning. */
    record Point(Fact fact, double x, double y) {
    }

    /**
     * The meaning map of the current, not archived facts.
     *
     * @param unplaced  facts without a usable embedding (the embedding model is missing, or they are being embedded
     *                  again)
     * @param explained the share of the differences between the facts each axis shows (0 to 1)
     * @param version   identifies the fact set and its embeddings (the projection is cached per version)
     */
    record MapView(List<Point> points, List<Fact> unplaced, double[] explained, String version) {
    }

    enum Range {
        WEEK, MONTH, YEAR, ALL;

        public static Range parse(String s) {
            for (Range r : values()) {
                if (r.name().equalsIgnoreCase(s == null ? "" : s.strip())) {
                    return r;
                }
            }
            throw new IllegalArgumentException("range must be week, month, year or all");
        }
    }

    /**
     * Facts on a time axis, with the day and week summaries as a lane.
     *
     * @param truncated some lanes were left out (the most recent are kept)
     */
    record TimelineView(Range range, Instant from, Instant to, List<FactTimeline.Lane> lanes, List<Episode> episodes,
                        boolean truncated) {
    }

    /** Events of one source, and how many no pass has read yet. */
    record Source(String source, long events, long waiting) {
    }

    /**
     * What became of what was read.
     *
     * @param stored      every fact row kept (every wording)
     * @param current     true now and not archived
     * @param updated     wordings replaced by a newer one
     * @param invalidated no longer true (the world ended them)
     * @param forgotten   forgotten by the owner (they are gone; counted from the owner's requests)
     */
    record FactCounts(long stored, long current, long archived, long pinned, long suggested, long sensitive, long updated,
                      long invalidated, long forgotten) {
    }

    /** The profile's versions and the summaries written. */
    record Written(long profileVersions, Long activeProfile, Instant activeProfileAt, long days, long weeks, long months) {
    }

    /** How memory is built, with the worker's state and its last passes. */
    record FlowView(List<Source> sources, FactCounts facts, Written written, ConsolidateMemory.Status worker,
                    List<ConsolidateMemory.Report> recent) {
    }

    GraphView graph();

    MapView map();

    TimelineView timeline(Range range);

    FlowView flow();
}

// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Sensitivity;

/** Facts with their sources and embeddings (docs/design.md 5.4, {@code fact} and {@code fact_source}). */
public interface FactStore {

    /** A fact and how similar it is to a query (cosine, -1 to 1). */
    record Scored(Fact fact, double similarity) {
    }

    /**
     * Which facts a search may return.
     *
     * @param now             "current" is judged at this time
     * @param currentOnly     only believed now and true now
     * @param includeArchived archived facts too ({@code recall}); never for automatic retrieval
     * @param maxSensitivity  nothing more private than this
     */
    record Filter(Instant now, boolean currentOnly, boolean includeArchived, Sensitivity maxSensitivity) {
        public static Filter current(Instant now) {
            return new Filter(now, true, true, Sensitivity.SENSITIVE);
        }
    }

    /**
     * A listing for the app.
     *
     * @param text     words in the statement or subject (blank: any)
     * @param subject  exact subject (blank: any)
     * @param validity {@code current}, {@code past} (expired or ended) or {@code all}
     * @param reviewed {@code false}: suggestions the owner has not reviewed yet (extracted, never looked at); {@code
     *                 true}: reviewed or written by the owner; {@code null}: either
     */
    record Query(String text, String subject, String kind, String validity, Sensitivity sensitivity, Boolean archived,
                 Boolean pinned, Boolean reviewed, Instant now, int limit, int offset) {

        public Query(String text, String subject, String kind, String validity, Sensitivity sensitivity, Boolean archived,
                     Boolean pinned, Instant now, int limit, int offset) {
            this(text, subject, kind, validity, sensitivity, archived, pinned, null, now, limit, offset);
        }
    }

    /**
     * Writes a reconciliation plan in one transaction: new facts with their sources and embeddings, ends of old
     * facts, and more sources for existing ones.
     *
     * @param embeddings the embedding of each new fact (a missing one is computed later)
     */
    void apply(Reconciliation.Plan plan, Map<UUID, float[]> embeddings);

    /** The nearest facts to an embedding, most similar first. */
    List<Scored> nearest(float[] embedding, int k, Filter filter);

    Optional<Fact> get(UUID id);

    List<Fact> list(Query query);

    long count(Query query);

    /**
     * Facts true in the world at {@code world}, as memory knew them at {@code known} (bi-temporal "as of"): a fact
     * the world ended later is still a true record of its time; an old wording replaced before {@code known} is not;
     * an end memory learned after {@code known} was not known then.
     * Latest learned first.
     */
    List<Fact> asOf(Instant world, Instant known, int limit);

    /** The versions of a fact: those it superseded and those that superseded it, oldest first. */
    List<Fact> versions(UUID id);

    /** Facts learned at or after {@code since} (still current), and facts that ended at or after it without a successor. */
    List<Fact> learnedSince(Instant since);

    List<Fact> endedSince(Instant since);

    /** Current, not archived, not pinned facts (the decay pass). */
    List<Fact> decayable(Instant now);

    void setArchived(Collection<UUID> ids, boolean archived);

    void setPinned(UUID id, boolean pinned);

    /** The owner reviewed these facts ({@code at} {@code null}: back to unreviewed). */
    void setReviewed(Collection<UUID> ids, Instant at);

    /** The facts were used in a prompt or a recall. */
    void touch(Collection<UUID> ids, Instant at);

    /** Deletes facts and their links; returns how many. */
    int delete(Collection<UUID> ids);

    /** Deletes the facts left with no source (their events were forgotten); returns how many. */
    int deleteOrphans();

    /** Facts without an embedding (the model was missing when they were written). */
    List<Fact> withoutEmbedding(int limit);

    void setEmbedding(UUID id, float[] embedding);

    /** How the nearest facts are found: pgvector's HNSW index, or an exact scan without pgvector. */
    String searchMode();

    /** The embedding size the schema was made for. */
    int dimensions();

    void deleteAll();
}

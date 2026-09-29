// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How automatically retrieved facts are ranked (docs/design.md 5.3, after Generative Agents), as a weighted sum of
 * normalised terms rather than a product, so one weak term does not hide a fact that is both very relevant and very
 * important:
 *
 * <pre>
 * score      = relevanceWeight · relevance + recencyWeight · recency + importanceWeight · importance
 * relevance  = cosine(question, fact), min-max normalised over the candidates
 * recency    = recencyBase ^ hours since the fact was last used or learned
 * importance = importance / 10
 * </pre>
 *
 * A relevance floor on the raw cosine keeps unrelated facts out: an empty section is better than noise. With a
 * single candidate (or equal similarities) min-max has no spread; relevance is then 1 for every candidate above the
 * floor.
 *
 * @param relevanceFloor raw cosine below which a fact is never retrieved automatically (set on bge-m3, where unrelated
 *                       sentences scored about 0.3 to 0.45; check it on the current model with the evaluation)
 */
public record RetrievalScoring(double relevanceWeight, double recencyWeight, double importanceWeight, double recencyBase,
                               double relevanceFloor) {

    public static final RetrievalScoring DEFAULT = new RetrievalScoring(1.0, 0.5, 0.7, 0.995, 0.45);

    /**
     * The floor for an embedding model: Qwen3-Embedding with its task instruction on the question gives lower cosines
     * than bge-m3 (measured on the owner's facts: a related fact 0.45 to 0.66, an unrelated one 0.16 to 0.35), so its
     * floor is 0.35; other models keep {@link #relevanceFloor}.
     */
    public double floorFor(String embedModel) {
        return embedModel != null && embedModel.startsWith("qwen3-embedding") ? Math.min(relevanceFloor, 0.35) : relevanceFloor;
    }

    /** A fact with its terms and score. */
    public record Scored(Fact fact, double similarity, double relevance, double recency, double importance, double score) {
    }

    /** The recency term of a fact at {@code now}. */
    public double recency(Fact f, Instant now) {
        Instant since = f.lastUsedAt() != null && f.lastUsedAt().isAfter(f.learnedAt()) ? f.lastUsedAt() : f.learnedAt();
        double hours = Math.max(0, Duration.between(since, now).toMillis() / 3_600_000.0);
        return Math.pow(recencyBase, hours);
    }

    /**
     * Scores candidates ({@code facts[i]} with cosine {@code similarities[i]}), drops those under the floor, and
     * returns the rest best first (ties: the most similar, then the newest).
     */
    public List<Scored> score(List<Fact> facts, List<Double> similarities, Instant now) {
        return score(facts, similarities, now, relevanceFloor);
    }

    /** As {@link #score(List, List, Instant)} with another floor ({@link Double#NEGATIVE_INFINITY}: keep every one). */
    public List<Scored> score(List<Fact> facts, List<Double> similarities, Instant now, double floor) {
        if (facts.size() != similarities.size()) {
            throw new IllegalArgumentException("one similarity per fact");
        }
        List<Integer> kept = new ArrayList<>();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < facts.size(); i++) {
            double s = similarities.get(i);
            if (s >= floor) {
                kept.add(i);
                min = Math.min(min, s);
                max = Math.max(max, s);
            }
        }
        double spread = max - min;
        List<Scored> out = new ArrayList<>();
        for (int i : kept) {
            Fact f = facts.get(i);
            double s = similarities.get(i);
            double relevance = spread > 1e-9 ? (s - min) / spread : 1.0;
            double recency = recency(f, now);
            double importance = f.importance() / 10.0;
            double score = relevanceWeight * relevance + recencyWeight * recency + importanceWeight * importance;
            out.add(new Scored(f, s, relevance, recency, importance, score));
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::similarity).reversed())
                .thenComparing((Scored x) -> x.fact().learnedAt(), Comparator.reverseOrder()));
        return out;
    }
}

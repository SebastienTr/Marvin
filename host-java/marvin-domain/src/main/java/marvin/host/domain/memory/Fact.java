// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A fact, bi-temporal (docs/design.md 5.4, after Graphiti): {@code validFrom}/{@code validTo} are the world's
 * time (when it was true), {@code learnedAt}/{@code expiredAt} ours (when memory believed it). Nothing is
 * deleted by consolidation: a changed world sets {@code validTo} and {@code expiredAt}, a better wording makes a
 * new version and sets {@code supersededBy} on the old one.
 *
 * @param subject     {@code owner}, {@code person:<name>}, {@code place:<name>} or {@code thing:<name>}
 * @param statement   one sentence, English
 * @param importance  1 to 10
 * @param confidence  0 to 1; stated by the owner: 1
 * @param extractedBy the model and prompt version that wrote it (empty for the owner's)
 * @param sources     the ids of the log events it came from (provenance), oldest first
 */
public record Fact(UUID id, String subject, String statement, FactKind kind, int importance, double confidence,
                   Sensitivity sensitivity, Instant validFrom, Instant validTo, Instant learnedAt, Instant expiredAt,
                   UUID supersededBy, Instant lastUsedAt, int useCount, boolean archived, boolean pinned,
                   FactOrigin origin, String extractedBy, List<Long> sources) {

    public Fact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(learnedAt, "learnedAt");
        kind = kind == null ? FactKind.STATE : kind;
        sensitivity = sensitivity == null ? Sensitivity.NORMAL : sensitivity;
        origin = origin == null ? FactOrigin.EXTRACTED : origin;
        extractedBy = extractedBy == null ? "" : extractedBy;
        importance = Math.clamp(importance, 1, 10);
        confidence = Math.clamp(confidence, 0.0, 1.0);
        sources = List.copyOf(sources == null ? List.of() : sources);
    }

    /** Believed now, and true in the world now: what the assembler may retrieve. */
    public boolean current(Instant now) {
        return expiredAt == null && (validTo == null || validTo.isAfter(now));
    }

    /** True in the world at {@code t}, in its latest wording (superseded versions left out). */
    public boolean validAt(Instant t) {
        return supersededBy == null && (validFrom == null || !validFrom.isAfter(t)) && (validTo == null || validTo.isAfter(t));
    }

    /** What memory believed at our time {@code t}. */
    public boolean believedAt(Instant t) {
        return !learnedAt.isAfter(t) && (expiredAt == null || expiredAt.isAfter(t));
    }

    public Fact withSources(List<Long> s) {
        return new Fact(id, subject, statement, kind, importance, confidence, sensitivity, validFrom, validTo, learnedAt,
                expiredAt, supersededBy, lastUsedAt, useCount, archived, pinned, origin, extractedBy, s);
    }

    public Fact withArchived(boolean a) {
        return new Fact(id, subject, statement, kind, importance, confidence, sensitivity, validFrom, validTo, learnedAt,
                expiredAt, supersededBy, lastUsedAt, useCount, a, pinned, origin, extractedBy, sources);
    }

    public Fact withPinned(boolean p) {
        return new Fact(id, subject, statement, kind, importance, confidence, sensitivity, validFrom, validTo, learnedAt,
                expiredAt, supersededBy, lastUsedAt, useCount, archived, p, origin, extractedBy, sources);
    }

    public Fact withExpiry(Instant expired, Instant validUntil, UUID supersededByFact) {
        return new Fact(id, subject, statement, kind, importance, confidence, sensitivity, validFrom, validUntil, learnedAt,
                expired, supersededByFact, lastUsedAt, useCount, archived, pinned, origin, extractedBy, sources);
    }

    public Fact withUse(Instant usedAt, int count) {
        return new Fact(id, subject, statement, kind, importance, confidence, sensitivity, validFrom, validTo, learnedAt,
                expiredAt, supersededBy, usedAt, count, archived, pinned, origin, extractedBy, sources);
    }

    /** The text that is embedded: the subject gives the statement its context. */
    public String embeddingText() {
        return embeddingText(subject, statement);
    }

    public static String embeddingText(String subject, String statement) {
        return "owner".equals(subject) ? statement : subject + ": " + statement;
    }
}

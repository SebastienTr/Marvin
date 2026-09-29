// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What to do with a candidate fact given the most similar current facts (docs/design.md 5.2, step 2: mem0's
 * update phase with Graphiti's invalidation instead of deletion).
 */
public sealed interface Operation {

    /** A new fact. */
    record Add() implements Operation {
    }

    /** The same fact, better worded or with new detail: a new version, the old one superseded. */
    record Update(UUID target, String statement) implements Operation {
    }

    /** The world changed: the old fact ends ({@code validTo}, world time; {@code null}: the event's time). */
    record Invalidate(UUID target, Instant validTo) implements Operation {
    }

    /** Already known: the events become more sources of it. */
    record Noop(UUID target) implements Operation {
    }

    /** The operation names, as in the model's JSON schema. */
    List<String> NAMES = List.of("ADD", "UPDATE", "INVALIDATE", "NOOP");

    /**
     * Reads the model's decision. {@code target} is the 1-based number of a fact in {@code similar}; a decision
     * that needs a target and has none that exists is an {@link Add} (never lose a candidate on a bad index), and a
     * {@link Noop} without a target is an {@link Add} too.
     */
    static Operation parse(String operation, Integer target, String statement, String validTo, List<Fact> similar,
                           ZoneId zone) {
        String op = operation == null ? "" : operation.strip().toUpperCase(Locale.ROOT);
        Fact t = target != null && target >= 1 && target <= similar.size() ? similar.get(target - 1) : null;
        if (t == null) {
            return new Add();
        }
        return switch (op) {
            case "UPDATE" -> new Update(t.id(), statement == null || statement.isBlank() ? null : statement.strip());
            case "INVALIDATE" -> new Invalidate(t.id(), FactCandidate.date(validTo, zone));
            case "NOOP" -> new Noop(t.id());
            default -> new Add();
        };
    }

    default String name() {
        return switch (this) {
            case Add a -> "ADD";
            case Update u -> "UPDATE";
            case Invalidate i -> "INVALIDATE";
            case Noop n -> "NOOP";
        };
    }
}

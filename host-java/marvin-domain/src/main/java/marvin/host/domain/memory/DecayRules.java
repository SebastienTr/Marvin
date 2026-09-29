// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Duration;
import java.time.Instant;

/**
 * How facts fade (docs/design.md 5.2, step 6): a fact's strength is its importance, halved every
 * {@code halfLifeDaysPerImportance × importance} days since it was last used (or learned). Below
 * {@code archiveBelow} it is archived: out of automatic retrieval, still found by {@code recall}, never deleted.
 * Pinned facts and the owner's own never decay.
 */
public record DecayRules(double halfLifeDaysPerImportance, double archiveBelow) {

    public static final DecayRules DEFAULT = new DecayRules(14, 0.05);

    public double strength(Fact f, Instant now) {
        Instant since = f.lastUsedAt() != null && f.lastUsedAt().isAfter(f.learnedAt()) ? f.lastUsedAt() : f.learnedAt();
        double days = Math.max(0, Duration.between(since, now).toMillis() / 86_400_000.0);
        double halfLife = halfLifeDaysPerImportance * f.importance();
        return f.importance() / 10.0 * Math.pow(0.5, days / halfLife);
    }

    /** Whether the nightly pass archives it now. */
    public boolean archives(Fact f, Instant now) {
        return !f.archived() && !f.pinned() && f.origin() != FactOrigin.OWNER && f.expiredAt() == null
                && strength(f, now) < archiveBelow;
    }
}

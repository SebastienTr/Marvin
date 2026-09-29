// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.time.Duration;

import marvin.host.domain.memory.DecayRules;
import marvin.host.domain.shared.TokenEstimator;

/**
 * The memory worker's tuning (the owner's choices are {@code MemorySettings}).
 *
 * @param batchGap          a silence this long ends a conversation (one extraction batch)
 * @param maxBatchEvents    most events in one extraction batch
 * @param pageSize          events read from the log at a time
 * @param similarK          current facts compared with each candidate (design: 10)
 * @param similarityFloor   facts less similar than this are not shown to the model; none left: the candidate is
 *                          added without asking it
 * @param maxDaysPerNight   day summaries written by one nightly pass at most (the first nights after a backfill)
 * @param tickSeconds       how often the worker checks whether a pass is due
 */
public record MemoryConfig(Duration batchGap, int maxBatchEvents, int pageSize, int similarK, double similarityFloor,
                           int maxDaysPerNight, double tickSeconds, TokenEstimator tokens, DecayRules decay) {

    public static final MemoryConfig DEFAULTS = new MemoryConfig(Duration.ofMinutes(10), 40, 200, 10, 0.3, 14, 30,
            TokenEstimator.DEFAULT, DecayRules.DEFAULT);
}

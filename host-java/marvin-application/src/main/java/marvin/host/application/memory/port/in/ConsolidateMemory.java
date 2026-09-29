// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** The memory worker (docs/design.md 5.2): passes on its own schedule, or now ("Consolidate now" in the app). */
public interface ConsolidateMemory {

    enum Pass {
        /** Extraction and reconciliation of the new events. */
        IDLE,
        /** The idle pass, then episodes, the profile rewrite, decay and retention. */
        NIGHTLY
    }

    /**
     * What a pass did.
     *
     * @param outcome {@code done}, {@code yielded} (the voice became active), {@code failed}, {@code skipped}
     * @param counts  events read, candidates, added, updated, invalidated, noop, dropped, episodes, archived ...
     * @param steps   each step's name and seconds, in order
     */
    record Report(Pass pass, String outcome, double startedAt, double seconds, String model, Map<String, Integer> counts,
                  List<Map<String, Object>> steps, String error, String fix) {
    }

    /**
     * The worker's state.
     *
     * @param state      {@code off}, {@code waiting}, {@code running} (with {@code pass} and {@code step})
     * @param nextNightAt when the next nightly pass is due (Unix seconds)
     */
    record Status(String state, Pass pass, String step, long pending, double lastIdleAt, double lastNightAt,
                  double nextNightAt, Report last) {
    }

    /** Runs a pass now, even without the idle wait (it still yields to the voice); completes with its report. */
    CompletableFuture<Report> consolidateNow(Pass pass);

    Status status();

    /** The last passes' reports, newest first (at most ten; the idle and the nightly ones among them). */
    default List<Report> recent() {
        Report last = status().last();
        return last == null ? List.of() : List.of(last);
    }
}

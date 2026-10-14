// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A finished background task becomes one event of memory's log (docs/design.md 6.1), redacted, once. */
class TaskFeedTest {

    @Test
    void aDoneTaskIsItsTitleSummaryAndTheStartOfItsResult() {
        MemoryEvent e = EventFeeds.task("t1", 1_790_000_000.5, "done", "Compare two laptops", "Marvin, compare two laptops",
                "The first one is lighter.", "## Laptops\n\n" + "x".repeat(3000), "", "voice").orElseThrow();
        assertThat(e.source()).isEqualTo(MemorySources.TASK);
        assertThat(e.kind()).isEqualTo("task_done");
        assertThat(e.externalRef()).isEqualTo("task:t1");
        assertThat(e.sensitivity()).isEqualTo(Sensitivity.NORMAL);
        assertThat(e.body()).startsWith("Background task “Compare two laptops” (asked: Marvin, compare two laptops) done: "
                + "The first one is lighter.\nResult: ## Laptops").endsWith("…");
        assertThat(e.body().length()).isLessThan(1700);
        assertThat(e.data()).containsEntry("status", "done").containsEntry("task_id", "t1").containsEntry("origin", "voice");
        assertThat(MemorySources.extractable(e)).isFalse();
    }

    @Test
    void aFailedTaskSaysWhyAndOtherStatesAreNotKept() {
        assertThat(EventFeeds.task("t2", 1, "failed", "Plan", "Plan", "", "", "Ran out of time (20 min)", "app").orElseThrow()
                .body()).isEqualTo("Background task “Plan” did not finish: Ran out of time (20 min)");
        for (String s : new String[] {"queued", "running", "needs_approval", "cancelled", "interrupted"}) {
            assertThat(EventFeeds.task("t3", 1, s, "x", "x", "", "", "", "app")).isEmpty();
        }
    }

    @Test
    void secretsInAResultAreRedacted() {
        MemoryEvent e = EventFeeds.task("t4", 1, "done", "Find the code", "Find the code", "Found it.",
                "The wifi password is hunter2 and my card is 4111 1111 1111 1111", "", "app").orElseThrow();
        assertThat(e.body()).doesNotContain("4111 1111 1111 1111");
    }
}

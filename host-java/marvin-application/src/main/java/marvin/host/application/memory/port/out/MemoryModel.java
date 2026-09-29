// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;

/**
 * The model's memory jobs (docs/design.md 5.2), each a prompt with structured output (Ollama's JSON schema
 * mode). The prompts are versioned; {@link #version()} is recorded with every fact they produce.
 */
public interface MemoryModel {

    /** Which server and model. */
    record Target(String host, String model) {
    }

    /** What a call cost, from the server's counters (0 when it gave none). */
    record Usage(int promptTokens, double promptSeconds, int outputTokens, double totalSeconds) {
        public static final Usage NONE = new Usage(0, 0, 0, 0);
    }

    /** One event of a batch, as the model reads it. */
    record Line(ZonedDateTime at, String who, String text) {
    }

    /**
     * @param lines   the batch
     * @param profile the current profile (who the owner is), may be empty
     * @param now     the batch's last event time: "tomorrow" and "next Tuesday" resolve against it
     */
    record ExtractRequest(List<Line> lines, String profile, ZonedDateTime now) {
    }

    record Extraction(List<FactCandidate.Raw> facts, Usage usage) {
    }

    /** @param similar the most similar current facts, numbered from 1 in the prompt */
    record ReconcileRequest(FactCandidate candidate, List<Fact> similar, ZonedDateTime eventTime) {
    }

    record Decision(String operation, Integer target, String statement, String validTo, Usage usage) {
    }

    /** @param items the events (a day) or the summaries (a week, a month) to summarise, oldest first */
    record SummaryRequest(EpisodeLevel level, LocalDate first, LocalDate last, List<String> items, int maxWords) {
    }

    /**
     * @param kept    lines to keep verbatim (the owner's)
     * @param learned statements learned since the last rewrite
     * @param ended   statements no longer true
     */
    record ProfileRequest(String previous, List<String> kept, List<String> learned, List<String> ended, int maxTokens) {
    }

    record Text(String text, Usage usage) {
    }

    /** The server or the model is missing. */
    final class Unavailable extends RuntimeException {
        private final String fix;

        public Unavailable(String message, String fix) {
            super(message);
            this.fix = fix == null ? "" : fix;
        }

        public String fix() {
            return fix;
        }
    }

    /** The caller asked to stop (the voice became active): the request was abandoned, the model stopped. */
    final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled");
        }
    }

    /** The model answered something that is not the requested structure. */
    final class BadOutput extends RuntimeException {
        public BadOutput(String message) {
            super(message);
        }
    }

    /*
     * Every call checks {@code cancelled} while the model writes and gives up (the server stops generating) when it
     * becomes true, throwing Cancelled.
     */

    Extraction extract(Target target, ExtractRequest request, BooleanSupplier cancelled);

    Decision reconcile(Target target, ReconcileRequest request, BooleanSupplier cancelled);

    Text summarize(Target target, SummaryRequest request, BooleanSupplier cancelled);

    Text rewriteProfile(Target target, ProfileRequest request, BooleanSupplier cancelled);

    /** The prompts' version, e.g. {@code memory-prompts/1}. */
    String version();
}

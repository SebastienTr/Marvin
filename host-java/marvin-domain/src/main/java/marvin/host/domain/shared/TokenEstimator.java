// SPDX-License-Identifier: MIT
package marvin.host.domain.shared;

/**
 * Token counts without the model's tokenizer (docs/design.md 5.3): characters per token, calibrated from the
 * model server's {@code prompt_eval_count}, with a safety margin.
 *
 * @param charsPerToken observed average (English with Qwen: about 4; French: about 3.5)
 * @param margin        added on top, 0.10 is 10 %
 */
public record TokenEstimator(double charsPerToken, double margin) {

    /** A cautious start before any measurement. */
    public static final TokenEstimator DEFAULT = new TokenEstimator(3.5, 0.10);

    public TokenEstimator {
        if (!(charsPerToken > 0.5)) {
            throw new IllegalArgumentException("charsPerToken must be > 0.5");
        }
        margin = Math.max(0, margin);
    }

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.codePointCount(0, text.length()) / charsPerToken * (1 + margin) - 1e-9);
    }

    /** A step towards a measured ratio (a moving average, so one odd prompt does not swing it). */
    public TokenEstimator calibrated(int chars, int tokens) {
        if (chars <= 0 || tokens <= 0) {
            return this;
        }
        double observed = (double) chars / tokens;
        return new TokenEstimator(Math.max(1.0, 0.8 * charsPerToken + 0.2 * observed), margin);
    }
}

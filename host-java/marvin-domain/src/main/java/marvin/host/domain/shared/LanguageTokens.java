// SPDX-License-Identifier: MIT
package marvin.host.domain.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Token estimates per language (docs/design.md 5.3): one {@link TokenEstimator} per conversation language, each
 * calibrated from the model server's {@code prompt_eval_count}. Immutable: calibrating returns a new instance.
 *
 * <p>A measurement is only taken when it is believable: a characters-per-token ratio outside {@code [1.5, 8]} means
 * the count did not cover the text it was compared with (the model server reused more or less of its cache than
 * expected), and is ignored rather than learned.
 */
public final class LanguageTokens {
    /** The plausible range of characters per token for Latin-script languages with a Qwen-like tokenizer. */
    public static final double MIN_RATIO = 1.5;
    public static final double MAX_RATIO = 8.0;

    private final TokenEstimator fallback;
    private final Map<String, TokenEstimator> byLanguage;

    public LanguageTokens(TokenEstimator fallback, Map<String, TokenEstimator> byLanguage) {
        this.fallback = fallback;
        this.byLanguage = Map.copyOf(byLanguage);
    }

    /** Before any measurement: the cautious default for every language. */
    public static LanguageTokens initial() {
        return new LanguageTokens(TokenEstimator.DEFAULT, Map.of());
    }

    /** The estimator of a language ({@code null} or unknown: the fallback). */
    public TokenEstimator of(String language) {
        return language == null ? fallback : byLanguage.getOrDefault(language, fallback);
    }

    public int estimate(String language, String text) {
        return of(language).estimate(text);
    }

    /**
     * A measurement: {@code chars} characters of prompt, in {@code language}, were {@code tokens} tokens. Returns the
     * calibrated estimates, or these when the measurement is not believable.
     */
    public LanguageTokens calibrated(String language, int chars, int tokens) {
        if (language == null || language.isEmpty() || chars <= 0 || tokens <= 0) {
            return this;
        }
        double ratio = (double) chars / tokens;
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
            return this;
        }
        Map<String, TokenEstimator> next = new LinkedHashMap<>(byLanguage);
        next.put(language, of(language).calibrated(chars, tokens));
        return new LanguageTokens(fallback, next);
    }

    /** Characters per token by language, for logs and the app. */
    public Map<String, Double> ratios() {
        Map<String, Double> out = new TreeMap<>();
        byLanguage.forEach((k, v) -> out.put(k, PyNumbers.round(v.charsPerToken(), 3)));
        return out;
    }
}

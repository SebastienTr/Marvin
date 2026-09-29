// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A fact the model proposes from a batch of events (docs/design.md 5.2, step 1), checked and normalised.
 *
 * @param validFrom world time it became true, when the conversation says so; {@code null}: from the event
 * @param validTo   world time it stops being true (a plan's date), {@code null}: open
 */
public record FactCandidate(String subject, String statement, FactKind kind, Instant validFrom, Instant validTo,
                            int importance, Sensitivity sensitivity, double confidence) {

    /** Longest statement kept: a fact is one sentence. */
    public static final int MAX_STATEMENT = 300;

    /** Words that make a fact {@code sensitive} by rule, whatever the model said (health, money). */
    private static final Pattern SENSITIVE_WORDS = Pattern.compile("(?i)\\b(health|ill|illness|sick|disease|diagnos\\w*|"
            + "doctor|hospital|surgery|medication|medicine|therapy|therapist|pregnan\\w*|allerg\\w*|blood pressure|"
            + "heart rate|depress\\w*|anxiety|salary|debt|loan|bank account|income|mortgage)\\b");

    public FactCandidate {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(statement, "statement");
        kind = kind == null ? FactKind.STATE : kind;
        sensitivity = sensitivity == null ? Sensitivity.NORMAL : sensitivity;
        importance = Math.clamp(importance, 1, 10);
        confidence = Math.clamp(confidence, 0.0, 1.0);
    }

    /** What the model returned for one fact, before any check. */
    public record Raw(String subject, String statement, String kind, String validFrom, String validTo, Number importance,
                      String sensitivity, Number confidence) {
    }

    /** A checked candidate, or why it was dropped. */
    public sealed interface Checked {
    }

    public record Accepted(FactCandidate candidate) implements Checked {
    }

    public record Dropped(String reason) implements Checked {
    }

    /**
     * Checks a raw candidate: drops empty ones and secrets, normalises the subject, clamps the numbers, parses
     * the dates (ISO dates or date-times; a date is its start in the owner's zone), and raises the sensitivity
     * to {@code sensitive} for health and money by rule and to at least that of its source events.
     */
    public static Checked check(Raw raw, ZoneId zone, Sensitivity sourceSensitivity) {
        if (raw == null || raw.statement() == null || raw.statement().isBlank()) {
            return new Dropped("empty statement");
        }
        Sensitivity s = Sensitivity.parse(raw.sensitivity(), Sensitivity.NORMAL);
        String statement = raw.statement().strip().replaceAll("\\s+", " ");
        if (s == Sensitivity.SECRET || Redaction.containsSecret(statement)) {
            return new Dropped("secret");
        }
        if (statement.length() > MAX_STATEMENT) {
            statement = statement.substring(0, MAX_STATEMENT).strip();
        }
        if (SENSITIVE_WORDS.matcher(statement).find()) {
            s = s.atLeast(Sensitivity.SENSITIVE);
        }
        s = s.atLeast(sourceSensitivity == Sensitivity.SECRET ? Sensitivity.SENSITIVE : sourceSensitivity);
        int importance = raw.importance() == null ? 5 : (int) Math.round(raw.importance().doubleValue());
        double confidence = raw.confidence() == null ? 0.7 : raw.confidence().doubleValue();
        return new Accepted(new FactCandidate(subject(raw.subject()), statement, FactKind.parse(raw.kind()),
                date(raw.validFrom(), zone), date(raw.validTo(), zone), importance, s, confidence));
    }

    /** {@code owner}, {@code person:<name>}, {@code place:<name>}, {@code thing:<name>}. */
    public static String subject(String s) {
        if (s == null || s.isBlank()) {
            return "owner";
        }
        String t = s.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("owner") || lower.equals("user") || lower.equals("the owner") || lower.equals("the user")
                || lower.equals("me") || lower.equals("i")) {
            return "owner";
        }
        int colon = t.indexOf(':');
        if (colon > 0) {
            String prefix = lower.substring(0, colon).strip();
            String name = t.substring(colon + 1).strip();
            if (name.isEmpty()) {
                return "owner";
            }
            return switch (prefix) {
                case "person", "place", "thing" -> prefix + ":" + name;
                case "owner", "user" -> "owner";
                default -> "thing:" + name;
            };
        }
        return "thing:" + t;
    }

    /** An ISO date or date-time; {@code null} when absent or unreadable. */
    public static Instant date(String s, ZoneId zone) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s.strip())) {
            return null;
        }
        String t = s.strip();
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException e) {
            // not with an offset
        }
        try {
            return LocalDateTime.parse(t).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            // not a local date-time
        }
        try {
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

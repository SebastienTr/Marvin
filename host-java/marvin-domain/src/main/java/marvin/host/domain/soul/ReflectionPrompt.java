// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import marvin.host.domain.shared.JsonText;

/**
 * The weekly reflection's request to the model (docs/design.md 8.2): the core, the learned part, the week's day summaries
 * and the owner's feedback (each line with its event's number), and the guardrails in words; the answer is a JSON object
 * with the new learned part, a rationale and the events behind it. The model only proposes: the domain checks the
 * guardrails, the owner decides.
 */
public final class ReflectionPrompt {
    /** Characters of evidence the model reads at most. */
    public static final int MAX_EVIDENCE_CHARS = 12_000;

    /** One thing the reflection reads: a day's summary ({@code event} 0) or a line the owner said (its event's number). */
    public record Evidence(long event, String when, String text) {
    }

    /** What the model proposed, before the guardrails. */
    public record Proposed(String learned, String rationale, List<Long> events) {
    }

    static final String SYSTEM = """
            You are the weekly reflection of Marvin, a small robot on its owner's desk. You read how the past week went \
            between Marvin and its owner and propose an updated "learned" part of Marvin's soul: a few short statements \
            about how to be with this owner (when to talk, how long answers should be, what to mention without being \
            asked, what to avoid). Facts about the owner's life do not belong here: memory keeps those.

            Rules (a proposal that breaks one is refused):
            - Learn only from the evidence: the owner's own words and reactions. When nothing new was learned, return \
            the current learned part unchanged.
            - Never aim at more time with Marvin, more talking, or making Marvin the owner's confidant; never \
            discourage other people or help. Marvin speaking up more is never a goal.
            - No persuasion, no flattery as a strategy, no feelings invented to steer the owner.
            - Honesty: never make Marvin claim what it does not sense or know.
            - Never grant Marvin a tool or a permission, never lift a rule, never contradict the core below.
            - At most %d statements, short, in English, in the third person ("Prefers short answers in the morning.").

            Answer with one JSON object only: {"learned": ["statement", ...], "rationale": "one or two sentences: what \
            changed and why", "events": [event numbers of the owner's lines behind the changes]}.""";

    /** Statements the learned part may hold. */
    public static final int MAX_STATEMENTS = 8;

    private ReflectionPrompt() {
    }

    public static String system() {
        return SYSTEM.formatted(MAX_STATEMENTS);
    }

    /** The week as the model reads it, cut to {@link #MAX_EVIDENCE_CHARS}. */
    public static String user(String core, String learned, List<Evidence> days, List<Evidence> feedback) {
        StringBuilder b = new StringBuilder();
        b.append("The core (written by the owner, never changed by you):\n").append(SoulText.normalise(core)).append("\n\n");
        String l = SoulText.normalise(learned);
        b.append("The current learned part:\n").append(l.isEmpty() ? "(empty)" : l).append("\n\n");
        int budget = MAX_EVIDENCE_CHARS;
        b.append("The week, day by day:\n");
        if (days.isEmpty()) {
            b.append("(no summary)\n");
        }
        for (Evidence d : days) {
            String line = "- " + d.when() + ": " + d.text().strip() + "\n";
            if (line.length() > budget) {
                break;
            }
            budget -= line.length();
            b.append(line);
        }
        b.append("\nWhat the owner said about how Marvin behaves (event number, when, what):\n");
        if (feedback.isEmpty()) {
            b.append("(nothing)\n");
        }
        for (Evidence f : feedback) {
            String line = "- [" + f.event() + "] " + f.when() + ": " + f.text().strip() + "\n";
            if (line.length() > budget) {
                break;
            }
            budget -= line.length();
            b.append(line);
        }
        return b.toString();
    }

    /**
     * The model's answer: the first JSON object in it. Events not in {@code known} are dropped (a model may invent
     * numbers). Statements beyond {@link #MAX_STATEMENTS} are dropped.
     *
     * @throws IllegalArgumentException no usable JSON object
     */
    public static Proposed parse(String answer, Set<Long> known) {
        if (answer == null) {
            throw new IllegalArgumentException("no answer");
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("the answer has no JSON object");
        }
        if (!(JsonText.parse(answer.substring(start, end + 1)) instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("the answer is not a JSON object");
        }
        List<String> lines = new ArrayList<>();
        Object learned = m.get("learned");
        if (learned instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank() && lines.size() < MAX_STATEMENTS) {
                    lines.add(s.strip());
                }
            }
        } else if (learned instanceof String s) {
            for (String x : SoulText.statements(s)) {
                if (lines.size() < MAX_STATEMENTS) {
                    lines.add(x);
                }
            }
        } else {
            throw new IllegalArgumentException("the answer has no learned part");
        }
        List<Long> events = new ArrayList<>();
        if (m.get("events") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n && known.contains(n.longValue()) && !events.contains(n.longValue())) {
                    events.add(n.longValue());
                }
            }
        }
        String rationale = m.get("rationale") instanceof String r ? r.strip() : "";
        return new Proposed(String.join("\n", lines), rationale, events);
    }
}

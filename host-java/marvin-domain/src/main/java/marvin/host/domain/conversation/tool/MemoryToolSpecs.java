// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the model is told about the memory tools (docs/design.md 5.3): {@code remember}, {@code recall} and
 * {@code forget}. Short, fixed descriptions: they are part of the cached system prompt, so they never change between
 * questions. None is online; none says a filler (they answer in tens of milliseconds).
 */
public final class MemoryToolSpecs {
    public static final String REMEMBER = "remember";
    public static final String RECALL = "recall";
    public static final String FORGET = "forget";
    public static final List<String> NAMES = List.of(FORGET, RECALL, REMEMBER);
    /** The periods {@code recall} understands. */
    public static final List<String> PERIODS = List.of("any", "today", "yesterday", "this_week", "last_week",
            "this_month", "last_month", "this_year");

    static final double TIMEOUT_S = 5.0;

    private MemoryToolSpecs() {
    }

    public static ToolSpec remember() {
        return new ToolSpec(REMEMBER, "Remember something the person asked you to remember, at once. One short sentence in "
                + "English, about the person or their world, as a fact (\"The owner's sister is called Claire\").",
                object(Map.of("statement", string("the fact to remember, one sentence in English", 300)), List.of("statement")),
                TIMEOUT_S, false, true, false);
    }

    public static ToolSpec recall() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", string("what to look for, a few words", 200));
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("type", "string");
        period.put("description", "when it happened, if the person said");
        period.put("enum", PERIODS);
        props.put("period", period);
        return new ToolSpec(RECALL, "Search your memory: facts (also past ones), day summaries and what was said. Use it "
                + "when the person asks about something from the past that your context does not give.",
                object(props, List.of("query")), TIMEOUT_S, false, true, false);
    }

    public static ToolSpec forget() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", string("what to forget, a few words", 200));
        props.put("confirm", string("the confirmation code a first call gave, only after the person said yes", 40));
        return new ToolSpec(FORGET, "Forget facts the person asks you to forget. A first call lists the matches and gives a "
                + "confirmation code; ask the person, and only if they say yes call again with the code.",
                object(props, List.of("query")), TIMEOUT_S, false, true, false);
    }

    private static Map<String, Object> string(String description, int maxLength) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        m.put("maxLength", maxLength);
        return m;
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        return m;
    }
}

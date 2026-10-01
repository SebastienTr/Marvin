// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code look} tool's words (docs/vision.md): Marvin takes one picture with its camera now, a vision model describes
 * it, and the description is what the conversation's model reads. Offered only while a camera can take a picture, so
 * Marvin never claims a sight it does not have. The description, short and plain, is the only trace of the picture.
 */
public final class Look {
    public static final String NAME = "look";
    public static final int MAX_QUESTION = 300;
    /** Taking the picture: opening the computer's camera and letting it settle takes about a second. */
    public static final double CAPTURE_S = 12.0;
    /** The vision model's description (a 27B model loads in seconds, then reads the picture). */
    public static final double DESCRIBE_S = 45.0;
    /** The vision model's description is cut to this many characters. */
    public static final int MAX_DESCRIPTION = 900;

    public static final String DESCRIPTION = "Take one picture with your camera now and get a short description of it. Use "
            + "it when the person asks what you see, what they are holding or showing you, what something looks like, or "
            + "to read something they hold up or on their screen. Never use it otherwise.";

    /** What the vision model is told: describe, answer the question, never guess. */
    public static final String SYSTEM_PROMPT = "You are the eyes of Marvin, a small robot on a desk. You get one picture "
            + "its camera has just taken, usually facing the person at the desk. Describe what it shows in at most three "
            + "short plain sentences, the most relevant first. When a question is given, answer it from the picture first. "
            + "When asked to read something, copy the text exactly as written, in its own language. Say plainly when the "
            + "picture is too dark or blurred or does not show what was asked; never guess beyond what is visible. Do not "
            + "guess who a person is, their age, or anything about their health. No markdown, no lists.";

    private Look() {
    }

    /** The tool's parameters schema. */
    public static Map<String, Object> parameters() {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "string");
        question.put("maxLength", (long) MAX_QUESTION);
        question.put("description", "What the person wants to know, in their words (\"what am I holding?\", \"read the "
                + "screen\"). Leave it out to describe what is in front of you.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("question", question);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", List.of());
        return p;
    }

    /** Offline; says "let me check" while the camera and the vision model work. */
    public static ToolSpec spec() {
        return new ToolSpec(NAME, DESCRIPTION, parameters(), CAPTURE_S + DESCRIBE_S + 3.0, false, false, true);
    }

    /** The vision model's user message (the picture goes with it). */
    public static String visionMessage(String question) {
        String q = question == null ? "" : question.strip();
        return q.isEmpty() ? "Describe what you see." : "The person asks: " + q;
    }

    /** The camera in words, for the conversation's model. */
    public static String cameraName(String source) {
        return "robot".equals(source) ? "your own camera, on the robot" : "the computer's camera, in front of the person";
    }

    /** What the conversation's model reads: which camera, and what the picture shows. */
    public static Map<String, Object> result(String source, int width, int height, String seen) {
        String s = seen == null ? "" : seen.strip();
        if (s.codePointCount(0, s.length()) > MAX_DESCRIPTION) {
            s = ToolResult.shorten(s, MAX_DESCRIPTION);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("camera", cameraName(source));
        m.put("picture", width + "x" + height + ", taken just now");
        m.put("seen", s.isEmpty() ? "nothing could be made out" : s);
        return m;
    }
}

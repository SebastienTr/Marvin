// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.Map;

/**
 * What Marvin and the task's model are told about approvals (docs/design.md 6.3): Marvin mentions a pending approval
 * aloud once, never nags; the model reads why a call did not run, and does not ask again.
 */
public final class ApprovalText {
    private static final Map<String, String> ASK = Map.of(
            "fr", "Pour « {title} », j'ai besoin de ton accord dans l'appli.",
            "en", "For “{title}”, I need your approval in the app.",
            "de", "Für „{title}“ brauche ich deine Zustimmung in der App.",
            "es", "Para «{title}», necesito tu aprobación en la aplicación.",
            "it", "Per «{title}» mi serve la tua approvazione nell'app.");

    private ApprovalText() {
    }

    /** The one sentence Marvin says when a task starts waiting for an approval. */
    public static String spoken(String language, String title) {
        return ASK.getOrDefault(language == null ? "en" : language, ASK.get("en")).replace("{title}", title);
    }

    /** The task's activity while it waits. */
    public static String waiting(String tool) {
        return "Waiting for your approval (" + tool + ")";
    }

    /** What the model reads when the owner rejected the call. */
    public static String rejected(String reason) {
        return "The owner did not approve this call" + (reason == null || reason.isBlank() ? "" : " (" + reason.strip() + ")")
                + ". It was not done. Do not ask again: finish with what you have and say what was not done.";
    }

    /** What the model reads when nobody answered in time. */
    public static String expired(String reason) {
        return "Nobody approved this call in time" + (reason == null || reason.isBlank() ? "" : " (" + reason.strip() + ")")
                + ". It was not done. Finish with what you have and say the owner can ask again.";
    }
}

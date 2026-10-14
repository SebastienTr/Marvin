// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import marvin.host.domain.conversation.tool.ToolAccess;
import marvin.host.domain.shared.JsonText;

/**
 * The owner's approval of one call of a tool that writes outside this computer, asked by a background task
 * (docs/design.md 6.3): the task waits in {@code needs_approval} until the owner approves or rejects it in the app, or it
 * expires. Immutable; the state machine is {@code pending -> approved | rejected | expired}, nothing else.
 *
 * @param id        the store's id, 0 before it is stored
 * @param taskId    the task that asks
 * @param tool      the tool's name ({@code gmail_create_draft})
 * @param access    always {@link ToolAccess#EXTERNAL_WRITE}: reads and local writes need no approval, irreversible tools
 *                  do not exist
 * @param created   when it was asked (Unix seconds)
 * @param decided   when it was decided, {@code null} while pending
 * @param summary   what the task is about, in a line (the task's title)
 * @param preview   what the owner is shown ({@link #preview(String, Map, Map)})
 * @param arguments the call's validated arguments, run as they are once approved
 * @param reason    why it was rejected or expired (the owner's words, or Marvin's), empty otherwise
 */
public record Approval(long id, String taskId, String tool, ToolAccess access, ApprovalState state, double created,
                       Double decided, String summary, Map<String, Object> preview, Map<String, Object> arguments,
                       String reason) {
    /** The preview of an email draft. */
    public static final String EMAIL_DRAFT = "email_draft";
    /** The preview of a call that has no preview of its own: the tool and its arguments. */
    public static final String TOOL_CALL = "tool_call";
    /** The longest body a preview shows. */
    public static final int MAX_BODY = 20_000;

    public Approval {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(state, "state");
        if (access != ToolAccess.EXTERNAL_WRITE) {
            throw new IllegalArgumentException("only a tool that writes outside this computer needs an approval: " + tool);
        }
        summary = summary == null ? "" : summary;
        preview = preview == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(preview));
        arguments = arguments == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        reason = reason == null ? "" : reason;
        if (state.decided() != (decided != null)) {
            throw new IllegalArgumentException("decided is set exactly when the approval is decided");
        }
    }

    /** A new approval, waiting for the owner. */
    public static Approval pending(String taskId, String tool, double now, String summary, Map<String, Object> preview,
                                   Map<String, Object> arguments) {
        return new Approval(0, taskId, tool, ToolAccess.EXTERNAL_WRITE, ApprovalState.PENDING, now, null, summary,
                preview(tool, arguments, preview), arguments, "");
    }

    public Approval withId(long newId) {
        return new Approval(newId, taskId, tool, access, state, created, decided, summary, preview, arguments, reason);
    }

    public Approval approve(double now) {
        return decide(ApprovalState.APPROVED, now, "");
    }

    public Approval reject(double now, String why) {
        return decide(ApprovalState.REJECTED, now, why);
    }

    public Approval expire(double now, String why) {
        return decide(ApprovalState.EXPIRED, now, why);
    }

    private Approval decide(ApprovalState to, double now, String why) {
        if (state.decided()) {
            throw new IllegalStateException("approval " + id + " is already " + state.id());
        }
        return new Approval(id, taskId, tool, access, to, created, now, summary, preview, arguments,
                why == null ? "" : why.strip());
    }

    /** The same, its preview pointing at what the call made ({@code open_url}: the draft in Gmail). */
    public Approval withOpenUrl(String url) {
        Map<String, Object> p = new LinkedHashMap<>(preview);
        p.put("open_url", url);
        return new Approval(id, taskId, tool, access, state, created, decided, summary, p, arguments, reason);
    }

    /**
     * A preview with the keys the app reads ({@code kind}, {@code to}, {@code subject}, {@code body}, {@code open_url}),
     * from a tool's own ({@code given}), or the call itself when it has none ({@link #TOOL_CALL}: the tool's name as the
     * subject, its arguments as the body).
     */
    public static Map<String, Object> preview(String tool, Map<String, Object> arguments, Map<String, Object> given) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (given != null && given.get("kind") instanceof String kind && !kind.isBlank()) {
            p.put("kind", kind);
            p.put("to", strings(given.get("to")));
            p.put("subject", given.get("subject") instanceof String s ? s : "");
            p.put("body", TaskText.cut(given.get("body") instanceof String b ? b : "", MAX_BODY));
            p.put("open_url", given.get("open_url") instanceof String u && !u.isBlank() ? u : null);
            return p;
        }
        p.put("kind", TOOL_CALL);
        p.put("to", List.of());
        p.put("subject", tool);
        p.put("body", TaskText.cut(JsonText.write(arguments == null ? Map.of() : arguments), MAX_BODY));
        p.put("open_url", null);
        return p;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        } else if (o instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    /** As the app lists it (docs: golden/api/get/approvals.json). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("task_id", taskId);
        m.put("tool", tool);
        m.put("class", access.name());
        m.put("state", state.id());
        m.put("created", created);
        m.put("decided", decided);
        m.put("summary", summary);
        m.put("preview", preview);
        return m;
    }

    /** The {@code approval} event of the app's stream. */
    public Map<String, Object> toEventMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("task_id", taskId);
        m.put("state", state.id());
        m.put("tool", tool);
        m.put("summary", summary);
        return m;
    }
}

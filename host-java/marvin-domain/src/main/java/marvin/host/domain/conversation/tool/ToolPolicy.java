// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The tool filter (docs/design.md 6.3): which tools may exist at all, and where. Checked by the tool registry on every
 * tool, built in or from a connector, so a tool that breaks a rule is never offered to the model, whatever its source
 * says about itself.
 *
 * <ul>
 * <li><b>Irreversible external actions are never tools.</b> Marvin never sends an email, deletes anything remote,
 * pays, shares or publishes: a tool whose name has a word for one of those ({@link #FORBIDDEN_WORDS}: {@code send},
 * {@code delete}, {@code trash}, {@code forward}, {@code reply} ...) is refused, whatever class it claims. The owner
 * acts in their own client.</li>
 * <li><b>A tool that writes outside this computer needs an approval</b>: it is offered only where an approval can be
 * asked for, a background task ({@link #offerable}); never in the conversation.</li>
 * </ul>
 *
 * <p>Local actions with an irreversible-sounding name that only touch Marvin's own memory ({@code forget}, which asks
 * for the owner's yes) are not external and are allowed.
 */
public final class ToolPolicy {

    /**
     * Words that name an irreversible or outward action; a tool name containing one as a word (split on underscores,
     * digits and camel case) is refused. {@code reply} and {@code respond} send; {@code share}, {@code publish},
     * {@code post} and {@code invite} reach other people; {@code spam} and {@code archive} change a remote mailbox.
     */
    public static final Set<String> FORBIDDEN_WORDS = Set.of(
            "send", "sends", "sending", "delete", "deletes", "deleting", "trash", "untrash", "remove", "forward", "reply",
            "respond", "purge", "erase", "destroy", "wipe", "unsubscribe", "pay", "payment", "purchase", "buy", "order",
            "transfer", "share", "publish", "post", "submit", "invite", "spam", "archive", "move", "drop", "revoke",
            "cancel");

    /**
     * Stems refused anywhere in a word, so that a variant does not slip through ({@code resend}, {@code undelete},
     * {@code autoforward}); a false refusal ({@code list_senders}) is the safe side.
     */
    public static final Set<String> FORBIDDEN_STEMS = Set.of("send", "delet", "trash", "forward", "purge", "erase");

    private ToolPolicy() {
    }

    /** Why a tool may not exist, or {@code null} when it may. */
    public static String refusal(String name, ToolAccess access) {
        if (name == null || name.isBlank()) {
            return "a tool needs a name";
        }
        for (String w : words(name)) {
            if (FORBIDDEN_WORDS.contains(w) || FORBIDDEN_STEMS.stream().anyMatch(w::contains)) {
                return "'" + name + "' would " + w + ": irreversible or outward actions are never tools (Marvin drafts, "
                        + "the owner sends)";
            }
        }
        return null;
    }

    /** Whether a tool may exist ({@link #refusal} says why not). */
    public static boolean allowed(String name, ToolAccess access) {
        return refusal(name, access) == null;
    }

    /**
     * Whether a tool of this class may be offered in {@code surface}: a tool that writes outside this computer only where
     * an approval can be asked for ({@code approvals}: a background task with the owner's approvals).
     */
    public static boolean offerable(ToolAccess access, ToolSurface surface, boolean approvals) {
        if (access != ToolAccess.EXTERNAL_WRITE) {
            return true;
        }
        return surface == ToolSurface.TASK && approvals;
    }

    /** The words of a tool name: {@code gmail_createDraft2} gives {@code gmail}, {@code create}, {@code draft}. */
    static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        char prev = 0;
        for (char c : name.toCharArray()) {
            boolean boundary = !Character.isLetter(c)
                    || Character.isUpperCase(c) && Character.isLowerCase(prev);
            if (boundary && !w.isEmpty()) {
                out.add(w.toString().toLowerCase(Locale.ROOT));
                w.setLength(0);
            }
            if (Character.isLetter(c)) {
                w.append(c);
            }
            prev = c;
        }
        if (!w.isEmpty()) {
            out.add(w.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}

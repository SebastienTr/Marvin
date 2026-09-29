// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.List;

/** The sources of the event log and the kinds memory learns from. */
public final class MemorySources {
    /** What Marvin heard and answered. */
    public static final String CONVERSATION = "conversation";
    /** What the presence brain noticed. */
    public static final String BRAIN = "brain";
    /** What the owner did in the app or said with a tool ("remember that ..."): memory never undoes it. */
    public static final String OWNER = "owner";

    /** Every source, in the order the app lists their switches. */
    public static final List<String> ALL = List.of(CONVERSATION, BRAIN, OWNER);

    /** Kinds of owner events. */
    public static final String EDIT = "edit";
    public static final String REMEMBER = "remember";
    public static final String FORGET = "forget";
    public static final String PIN = "pin";
    public static final String PROFILE_EDIT = "profile_edit";

    private MemorySources() {
    }

    /** Whether the worker extracts facts from this event (the rest only goes into episodes). */
    public static boolean extractable(MemoryEvent e) {
        return CONVERSATION.equals(e.source()) && ("heard".equals(e.kind()) || "reply".equals(e.kind()))
                && !e.body().isBlank() && !(e.data().get("error") instanceof String s && !s.isEmpty());
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

/**
 * When Marvin tells the owner a task is finished (docs/intelligence.md): at once when they are there and not talking
 * with it, later otherwise. The app always shows it.
 *
 * <ul>
 * <li>a conversation is going on: {@link Decision#WAIT}, decided again when it is over;</li>
 * <li>the voice is off, it is quiet hours, or the presence sensor says nobody is there: {@link Decision#NEXT_TURN}, the
 * next answer mentions it (and Marvin says it when someone arrives);</li>
 * <li>someone is there: {@link Decision#SPEAK_NOW}; with no presence sensor, someone who talked with Marvin in the last
 * {@link #RECENT_S} seconds counts as there.</li>
 * </ul>
 */
public final class TaskAnnouncement {
    /** Without a presence sensor, a conversation this recent says someone is there. */
    public static final double RECENT_S = 15 * 60;

    public enum Decision { SPEAK_NOW, NEXT_TURN, WAIT }

    private TaskAnnouncement() {
    }

    /**
     * @param present     the presence sensor's word ({@code null}: no sensor says anything)
     * @param sinceTalkS  seconds since the last conversation ({@link Double#POSITIVE_INFINITY}: none)
     */
    public static Decision decide(boolean voiceOn, boolean conversing, boolean quiet, Boolean present, double sinceTalkS) {
        if (conversing) {
            return Decision.WAIT;
        }
        if (!voiceOn || quiet || Boolean.FALSE.equals(present)) {
            return Decision.NEXT_TURN;
        }
        if (Boolean.TRUE.equals(present)) {
            return Decision.SPEAK_NOW;
        }
        return sinceTalkS < RECENT_S ? Decision.SPEAK_NOW : Decision.NEXT_TURN;
    }
}

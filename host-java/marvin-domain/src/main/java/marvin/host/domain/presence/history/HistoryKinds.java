// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.history;

import java.util.List;

import marvin.host.domain.presence.event.EventKind;

/**
 * The host's own events, which mark the holes in the history (stats.py): the host started or stopped,
 * the robot went quiet or came back ({@code data}: whether someone is present and seated right now).
 */
public final class HistoryKinds {
    public static final String HOST_STARTED = "host_started";
    public static final String HOST_STOPPED = "host_stopped";
    public static final String ROBOT_OFFLINE = "robot_offline";
    public static final String ROBOT_ONLINE = "robot_online";

    /** The host's markers (shown in the Log panel as "host"). */
    public static final List<String> SYSTEM = List.of(HOST_STARTED, HOST_STOPPED, ROBOT_OFFLINE, ROBOT_ONLINE);

    /** Left out of the events list with {@code quiet=1}. */
    public static final List<String> VITALS = List.of(EventKind.VITALS_ACQUIRED.wireName(),
            EventKind.VITALS_LOST.wireName());

    private HistoryKinds() {
    }
}

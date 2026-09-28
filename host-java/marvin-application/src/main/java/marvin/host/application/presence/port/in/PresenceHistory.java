// SPDX-License-Identifier: MIT
package marvin.host.application.presence.port.in;

import java.time.LocalDate;
import java.util.List;

import marvin.host.domain.presence.history.DayStats;
import marvin.host.domain.presence.history.StoredEvent;

/** The presence history: days, the week, the events list, and whether the robot is sending. */
public interface PresenceHistory {

    /** One local day, as of now (still open intervals end now). */
    DayStats day(LocalDate day);

    /** Today, recomputed at most every 2 s and after every event. */
    DayStats today();

    /** The {@code days} days ending with {@code last}, oldest first. */
    List<DayStats.DaySummary> history(LocalDate last, int days);

    /** The latest events, newest first; {@code quiet}: without the vital sign events. */
    List<StoredEvent> recent(int limit, long sinceId, boolean quiet);

    /** The local day of a wall-clock time. */
    LocalDate dayOf(double ts);


    /** Frames are coming in (the brain's clock moved in the last seconds). */
    boolean online();

    /** Frames came in at least once since start. */
    boolean everOnline();

    /** Seconds since the person left, while nobody is present; {@code null} otherwise. */
    Double awaySeconds();
}

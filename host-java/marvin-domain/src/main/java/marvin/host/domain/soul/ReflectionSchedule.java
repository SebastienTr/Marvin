// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * When the weekly reflection runs (docs/design.md 8.2): once a week, from Sunday night ({@link #DAY} at {@link #HOUR});
 * the host is a laptop that sleeps, so a reflection missed then runs at the first chance after (the first idle moment the
 * caller finds), once.
 */
public final class ReflectionSchedule {
    public static final DayOfWeek DAY = DayOfWeek.SUNDAY;
    public static final int HOUR = 21;

    private ReflectionSchedule() {
    }

    /** The last time a reflection became due at or before {@code now}. */
    public static ZonedDateTime lastDue(ZonedDateTime now) {
        ZonedDateTime candidate = now.with(TemporalAdjusters.previousOrSame(DAY)).withHour(HOUR).withMinute(0).withSecond(0)
                .withNano(0);
        return candidate.isAfter(now) ? candidate.minusWeeks(1) : candidate;
    }

    /**
     * Whether a reflection is due now: none ran since the last Sunday night ({@code lastRun}: Unix seconds, {@code null}
     * when none ever ran).
     */
    public static boolean due(Double lastRun, ZonedDateTime now) {
        double boundary = lastDue(now).toEpochSecond();
        return lastRun == null || lastRun < boundary;
    }
}

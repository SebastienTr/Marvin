// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** The summary hierarchy: a day from its events, a week from its days, a month from the weeks that start in it. */
public enum EpisodeLevel {
    DAY, WEEK, MONTH;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EpisodeLevel parse(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }

    /** The first local day of the period that contains {@code day} (weeks start on Monday). */
    public LocalDate start(LocalDate day) {
        return switch (this) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
        };
    }

    /** The first local day after the period that starts on {@code start}. */
    public LocalDate end(LocalDate start) {
        return switch (this) {
            case DAY -> start.plusDays(1);
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
        };
    }

    /** The level the summaries of this one are made from ({@code null} for a day: made from events). */
    public EpisodeLevel below() {
        return switch (this) {
            case DAY -> null;
            case WEEK -> DAY;
            case MONTH -> WEEK;
        };
    }

    /** Most words a summary of this level may have. */
    public int maxWords() {
        return switch (this) {
            case DAY -> 200;
            case WEEK -> 250;
            case MONTH -> 300;
        };
    }
}

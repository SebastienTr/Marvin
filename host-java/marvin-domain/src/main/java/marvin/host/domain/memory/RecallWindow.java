// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * The period a {@code recall} looks in, as local days {@code [from, to)} in the owner's zone. {@code any} has no
 * bounds. Weeks start on Monday.
 */
public record RecallWindow(String period, LocalDate from, LocalDate to) {

    public static final RecallWindow ANY = new RecallWindow("any", null, null);

    public boolean bounded() {
        return from != null;
    }

    public Instant start(ZoneId zone) {
        return from == null ? null : from.atStartOfDay(zone).toInstant();
    }

    public Instant end(ZoneId zone) {
        return to == null ? null : to.atStartOfDay(zone).toInstant();
    }

    /** Whether a time is in the window. */
    public boolean contains(Instant t, ZoneId zone) {
        if (!bounded()) {
            return true;
        }
        LocalDate d = t.atZone(zone).toLocalDate();
        return !d.isBefore(from) && d.isBefore(to);
    }

    /** Whether {@code [a, b)} ({@code null}: open) overlaps the window. */
    public boolean overlaps(Instant a, Instant b, ZoneId zone) {
        if (!bounded()) {
            return true;
        }
        return (a == null || a.isBefore(end(zone))) && (b == null || b.isAfter(start(zone)));
    }

    /** The window of a period name at {@code now}; an unknown name is {@link #ANY}. */
    public static RecallWindow of(String period, Instant now, ZoneId zone) {
        String p = period == null ? "any" : period.strip().toLowerCase(Locale.ROOT);
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = today.withDayOfMonth(1);
        return switch (p) {
            case "today" -> new RecallWindow(p, today, today.plusDays(1));
            case "yesterday" -> new RecallWindow(p, today.minusDays(1), today);
            case "this_week" -> new RecallWindow(p, monday, today.plusDays(1));
            case "last_week" -> new RecallWindow(p, monday.minusWeeks(1), monday);
            case "this_month" -> new RecallWindow(p, first, today.plusDays(1));
            case "last_month" -> new RecallWindow(p, first.minusMonths(1), first);
            case "this_year" -> new RecallWindow(p, today.withDayOfYear(1), today.plusDays(1));
            default -> ANY;
        };
    }
}

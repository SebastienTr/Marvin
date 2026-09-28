// SPDX-License-Identifier: MIT
package marvin.host.domain.shared;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Local calendar days in the owner's time zone (stats.py {@code day_bounds}, {@code local_day}). */
public record LocalDays(ZoneId zone) {

    /** Start and end of a local day, Unix seconds (23 or 25 h long around DST changes). */
    public double[] bounds(LocalDate day) {
        double lo = day.atStartOfDay(zone).toEpochSecond();
        double hi = day.plusDays(1).atStartOfDay(zone).toEpochSecond();
        return new double[] {lo, hi};
    }

    /** The local day a wall-clock time falls in. */
    public LocalDate dayOf(double ts) {
        long ms = (long) Math.floor(ts * 1000.0);
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
    }

    /** Minutes since local midnight. */
    public int minuteOfDay(double ts) {
        var t = Instant.ofEpochMilli((long) Math.floor(ts * 1000.0)).atZone(zone);
        return t.getHour() * 60 + t.getMinute();
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.history;

import java.time.LocalDate;

import marvin.host.domain.shared.LocalDays;
import java.util.ArrayList;
import java.util.List;

import marvin.host.domain.shared.PyNumbers;

/**
 * What happened on one local day (stats.py {@code day_stats}). Durations in seconds, times in Unix
 * seconds, rounded as the Python host rounds them.
 *
 * @param now           the time it was computed, if within the day
 * @param presentS      time someone was in front of the robot
 * @param seatedS       time at the desk, {@code sat_down} .. {@code stood_up} / {@code left}
 * @param sessions      sitting sessions, joining those less than {@link #MIN_BREAK_S} apart
 * @param breaks        the gaps between two sessions of the day
 * @param breakS        their total
 * @param longestS      the longest session
 * @param reminders     {@code still_long} reminders
 * @param firstArrival  {@code null} if nobody came
 * @param lastDeparture {@code null} if nobody came, or if they are still there
 * @param breathRate    average of the reliable minutes, or {@code null}
 * @param heartRate     average of the reliable minutes, or {@code null}
 */
public record DayStats(LocalDate date, double start, double end, Double now, double presentS, double seatedS,
                       int sessions, int breaks, double breakS, double longestS, int reminders, Double firstArrival,
                       Double lastDeparture, Double breathRate, Double heartRate, Timeline timeline) {

    /** Standing up for less than this does not end a sitting session. */
    public static final double MIN_BREAK_S = 60.0;

    /** The intervals of the day (rounded to 0.1 s) for the timeline. */
    public record Timeline(List<Interval> present, List<Interval> seated, List<Interval> breaks,
                           List<Double> reminders) {
    }

    /**
     * {@code events} must start early enough to know the state at midnight (the store passes the last
     * 36 hours before the day); {@code samples} are the per-minute averages.
     */
    public static DayStats compute(List<StoredEvent> events, LocalDate day, LocalDays days, double now, boolean live,
                                   List<Sample> samples) {
        double[] b = days.bounds(day);
        double lo = b[0];
        double hi = b[1];
        double end = Math.min(hi, now);
        double[] alive = samples.stream().mapToDouble(Sample::ts).toArray();
        Folded f = Folded.fold(events, now, live, alive);
        List<Interval> present = end > lo ? Interval.clip(f.present(), lo, end) : List.of();
        List<Interval> seated = end > lo ? Interval.clip(f.seated(), lo, end) : List.of();
        List<Interval> sessions = Interval.merge(seated, MIN_BREAK_S);
        List<Interval> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < sessions.size(); i++) {
            gaps.add(new Interval(sessions.get(i).end(), sessions.get(i + 1).start()));
        }
        boolean hereNow = live && f.openPresent() && lo <= now && now < hi;

        double breathSum = 0;
        double heartSum = 0;
        int vitals = 0;
        for (Sample s : samples) {
            if (lo <= s.ts() && s.ts() < hi && s.breath() != null && s.heart() != null) {
                breathSum += s.breath();
                heartSum += s.heart();
                vitals++;
            }
        }
        double longest = 0.0;
        for (Interval s : sessions) {
            longest = Math.max(longest, s.length());
        }
        List<Double> reminders = f.reminders().stream().filter(t -> lo <= t && t < end).toList();
        return new DayStats(day, lo, hi, lo <= now && now < hi ? now : null,
                PyNumbers.round(Interval.total(present), 1),
                PyNumbers.round(Interval.total(seated), 1),
                sessions.size(), gaps.size(),
                PyNumbers.round(Interval.total(gaps), 1),
                PyNumbers.round(longest, 1),
                reminders.size(),
                present.isEmpty() ? null : present.getFirst().start(),
                present.isEmpty() || hereNow ? null : present.getLast().end(),
                vitals > 0 ? PyNumbers.round(breathSum / vitals, 1) : null,
                vitals > 0 ? PyNumbers.round(heartSum / vitals, 1) : null,
                new Timeline(rounded(present), rounded(seated), rounded(gaps),
                        reminders.stream().map(t -> PyNumbers.round(t, 1)).toList()));
    }

    private static List<Interval> rounded(List<Interval> intervals) {
        return intervals.stream()
                .map(i -> new Interval(PyNumbers.round(i.start(), 1), PyNumbers.round(i.end(), 1)))
                .toList();
    }

    /** The short form of the week chart (store.py {@code history}). */
    public DaySummary summary() {
        return new DaySummary(date, seatedS, presentS, sessions, breaks, longestS);
    }

    /** One bar of the week chart. */
    public record DaySummary(LocalDate date, double seatedS, double presentS, int sessions, int breaks,
                             double longestS) {
    }
}

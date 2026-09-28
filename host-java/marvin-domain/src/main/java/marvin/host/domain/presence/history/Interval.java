// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A time interval, wall clock, Unix seconds, with the stats.py helpers on lists of them. */
public record Interval(double start, double end) {

    public double length() {
        return end - start;
    }

    /** Joins intervals separated by less than {@code gap} seconds (stats.py {@code merge}). */
    public static List<Interval> merge(List<Interval> intervals, double gap) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingDouble(Interval::start).thenComparingDouble(Interval::end));
        List<double[]> out = new ArrayList<>();
        for (Interval i : sorted) {
            if (!out.isEmpty() && i.start - out.getLast()[1] < gap) {
                double[] last = out.getLast();
                last[1] = Math.max(last[1], i.end);
            } else {
                out.add(new double[] {i.start, i.end});
            }
        }
        return out.stream().map(a -> new Interval(a[0], a[1])).toList();
    }

    /** The parts of the intervals within [lo, hi) (stats.py {@code clip}). */
    public static List<Interval> clip(List<Interval> intervals, double lo, double hi) {
        List<Interval> out = new ArrayList<>();
        for (Interval i : intervals) {
            if (i.end > lo && i.start < hi && Math.min(i.end, hi) > Math.max(i.start, lo)) {
                out.add(new Interval(Math.max(i.start, lo), Math.min(i.end, hi)));
            }
        }
        return out;
    }

    public static double total(List<Interval> intervals) {
        double s = 0;
        for (Interval i : intervals) {
            s += i.length();
        }
        return s;
    }
}

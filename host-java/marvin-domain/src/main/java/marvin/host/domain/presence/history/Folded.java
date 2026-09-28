// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.history;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Present and seated intervals rebuilt from a stream of stored events (stats.py {@code fold}).
 *
 * @param reminders    {@code still_long} times
 * @param openPresent  the last present interval is still going on
 * @param openSeated   the last seated interval is still going on
 */
public record Folded(List<Interval> present, List<Interval> seated, List<Double> reminders, boolean openPresent,
                     boolean openSeated) {

    /**
     * {@code now} closes the intervals still open at the end when {@code live} (the host is running right
     * now); otherwise they are closed at the last sign of life. {@code alive} are extra timestamps
     * proving the host was running (the per-minute samples), used to close intervals cut short by a crash.
     */
    public static Folded fold(List<StoredEvent> events, double now, boolean live, double[] alive) {
        List<StoredEvent> evs = new ArrayList<>(events);
        evs.sort(Comparator.comparingDouble(StoredEvent::ts).thenComparingLong(StoredEvent::id));
        double[] life = alive.clone();
        Arrays.sort(life);
        return new Folder(life).run(evs, now, live);
    }

    private static final class Folder {
        private final double[] alive;
        private final List<Interval> present = new ArrayList<>();
        private final List<Interval> seated = new ArrayList<>();
        private final List<Double> reminders = new ArrayList<>();
        private Double pStart;
        private Double sStart;
        private Double lastTs;

        Folder(double[] alive) {
            this.alive = alive;
        }

        double lastAlive(double before) {
            double t = lastTs != null ? lastTs : before;
            int i = bisectRight(alive, before) - 1;
            if (i >= 0 && alive[i] > t) {
                t = alive[i];
            }
            return Math.min(t, before);
        }

        void close(double at) {
            if (sStart != null) {
                seated.add(new Interval(sStart, Math.max(sStart, at)));
                sStart = null;
            }
            if (pStart != null) {
                present.add(new Interval(pStart, Math.max(pStart, at)));
                pStart = null;
            }
        }

        Folded run(List<StoredEvent> evs, double now, boolean live) {
            for (StoredEvent e : evs) {
                switch (e.kind()) {
                    case HistoryKinds.HOST_STARTED -> close(lastAlive(e.ts()));
                    case HistoryKinds.HOST_STOPPED, HistoryKinds.ROBOT_OFFLINE -> close(e.ts());
                    case HistoryKinds.ROBOT_ONLINE -> {
                        if (e.flag("present") && pStart == null) {
                            pStart = e.ts();
                        }
                        if (e.flag("seated") && sStart == null) {
                            sStart = e.ts();
                        }
                    }
                    case "arrived" -> {
                        if (pStart == null) {
                            pStart = e.ts();
                        }
                    }
                    case "left" -> close(e.ts());
                    case "sat_down" -> {
                        if (pStart == null) {             // a seated person is present, even if ARRIVED was missed
                            pStart = e.ts();
                        }
                        if (sStart == null) {
                            sStart = e.ts();
                        }
                    }
                    case "stood_up" -> {
                        if (sStart != null) {
                            seated.add(new Interval(sStart, Math.max(sStart, e.ts())));
                            sStart = null;
                        }
                    }
                    case "still_long" -> reminders.add(e.ts());
                    default -> {
                    }
                }
                lastTs = e.ts();
            }
            boolean openPresent = false;
            boolean openSeated = false;
            if (pStart != null || sStart != null) {
                openPresent = pStart != null;
                openSeated = sStart != null;
                double end = live ? now : lastAlive(now);
                close(end);
                if (!live) {
                    openPresent = openSeated = false;
                }
            }
            return new Folded(List.copyOf(present), List.copyOf(seated), List.copyOf(reminders), openPresent,
                    openSeated);
        }
    }

    static int bisectRight(double[] a, double x) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (x < a[mid]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }
}

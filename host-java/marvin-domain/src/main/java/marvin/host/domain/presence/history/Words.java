// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.history;

import java.util.Locale;

import marvin.host.domain.shared.PyNumbers;

/** The app's sentences (stats.py {@code duration}, {@code describe}, {@code status}). */
public final class Words {
    private Words() {
    }

    /** 42 s, 12 min, 1 h 05, 3 h. */
    public static String duration(double seconds) {
        double s = Math.max(0.0, seconds);
        if (s < 60) {
            return PyNumbers.fixed(s, 0) + " s";
        }
        long m = (long) Math.floor(s / 60);
        if (m < 60) {
            return m + " min";
        }
        long h = m / 60;
        m = m % 60;
        return m != 0 ? String.format(Locale.ROOT, "%d h %02d", h, m) : h + " h";
    }

    /** One short sentence for the events list. */
    public static String describe(StoredEvent e) {
        String k = e.kind();
        return switch (k) {
            case "arrived" -> "You came in";
            case "left" -> "sensor restarted".equals(e.detail()) ? "Marvin lost track of you (sensor restarted)"
                    : "You left";
            case "approached" -> "You came close to Marvin";
            case "sat_down" -> "You sat down";
            case "stood_up" -> {
                Double s = e.number("seated_s");
                yield s != null ? "You stood up after " + duration(s) : "You stood up";
            }
            case "still_long" -> {
                Double s = e.number("seated_s");
                yield s != null ? "Time for a break: seated for " + duration(s) : "Time for a break";
            }
            case "vitals_acquired" -> {
                Double b = e.number("breath_rate");
                Double h = e.number("heart_rate");
                yield b != null && h != null
                        ? "Breathing " + PyNumbers.fixed(b, 0) + "/min, heart " + PyNumbers.fixed(h, 0) + "/min"
                        : "Vital signs measured";
            }
            case "vitals_lost" -> e.detail().isEmpty() ? "Vital signs paused"
                    : "Vital signs paused (" + e.detail() + ")";
            case HistoryKinds.HOST_STARTED -> "Marvin started";
            case HistoryKinds.HOST_STOPPED -> "Marvin stopped";
            case HistoryKinds.ROBOT_OFFLINE -> "Lost contact with the robot";
            case HistoryKinds.ROBOT_ONLINE -> "Connected to the robot";
            default -> {
                if (!e.detail().isEmpty()) {
                    yield e.detail();
                }
                String t = k.replace('_', ' ');
                yield t.isEmpty() ? t : t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1).toLowerCase(Locale.ROOT);
            }
        };
    }

    /**
     * The one-line status at the top of the app, a second line, and a mood (offline, break, seated,
     * present, away, asleep).
     */
    public record Status(String text, String detail, String mood) {
    }

    public static Status status(boolean online, boolean present, boolean seated, double seatedS, Double awayS,
                                Double breath, Double heart, double breakS, boolean everOnline) {
        String vitals = "";
        if (breath != null && heart != null && Double.isFinite(breath) && Double.isFinite(heart)) {
            vitals = "Breathing " + PyNumbers.fixed(breath, 0) + "/min, heart " + PyNumbers.fixed(heart, 0) + "/min";
        }
        if (!online) {
            return new Status(everOnline ? "Marvin is offline" : "Waiting for Marvin to connect", "", "offline");
        }
        if (seated) {
            if (seatedS >= breakS) {
                return new Status("You've been sitting for " + duration(seatedS) + ". Time to stretch your legs.",
                        vitals, "break");
            }
            String text = seatedS < 60 ? "You just sat down" : "You've been at your desk for " + duration(seatedS);
            return new Status(text, vitals, "seated");
        }
        if (present) {
            return new Status("You're here, up and about", vitals, "present");
        }
        if (awayS != null && awayS < 60) {
            return new Status("You just left. Marvin is keeping an eye out.", "", "away");
        }
        return new Status("Marvin is asleep. Nobody around.", "", "asleep");
    }
}

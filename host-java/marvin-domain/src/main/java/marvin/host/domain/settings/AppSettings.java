// SPDX-License-Identifier: MIT
package marvin.host.domain.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The app's settings.
 *
 * @param breakIntervalMin seated this long, minutes: time for a break (the brain's {@code still_long});
 *                         an {@link Integer} when whole, as the Python host keeps it
 * @param quietHours       no sounds or reminders in this window
 * @param voice            the voice starts with the host (turned on and off in the app)
 * @param clock            {@code "24h"} or {@code "12h"}
 * @param uiSounds         short sounds in the browser
 */
public record AppSettings(Number breakIntervalMin, QuietHours quietHours, boolean voice, String clock,
                          boolean uiSounds) {

    /** The keys, in the order the app lists them. */
    public static final List<String> KEYS = List.of("break_interval_min", "quiet_hours", "voice", "clock", "ui_sounds");

    /**
     * @param start {@code HH:MM}, local time
     * @param end   {@code HH:MM}; before {@code start}: the window crosses midnight
     */
    public record QuietHours(boolean enabled, String start, String end) {
        public static final QuietHours DEFAULT = new QuietHours(false, "22:00", "07:00");

        /** Whether {@code minuteOfDay} (local) is inside the window. */
        public boolean contains(int minuteOfDay) {
            if (!enabled) {
                return false;
            }
            int a = parseHhmm(start);
            int b = parseHhmm(end);
            return a <= b ? a <= minuteOfDay && minuteOfDay < b : minuteOfDay >= a || minuteOfDay < b;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled);
            m.put("start", start);
            m.put("end", end);
            return m;
        }
    }

    public AppSettings {
        Objects.requireNonNull(breakIntervalMin, "breakIntervalMin");
        Objects.requireNonNull(quietHours, "quietHours");
        Objects.requireNonNull(clock, "clock");
    }

    /** The defaults, with the brain's break interval (seconds). */
    public static AppSettings defaults(double stillLongS) {
        double minutes = Math.round(stillLongS / 60 * 10) / 10.0;
        return new AppSettings(whole(minutes), QuietHours.DEFAULT, false, "24h", true);
    }

    public double breakIntervalS() {
        return breakIntervalMin.doubleValue() * 60;
    }

    /** As JSON-like values (Boolean, Number, String, Map), in the app's key order. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("break_interval_min", breakIntervalMin);
        m.put("quiet_hours", quietHours.toMap());
        m.put("voice", voice);
        m.put("clock", clock);
        m.put("ui_sounds", uiSounds);
        return m;
    }

    /** The value of one key, as in {@link #toMap()}. */
    public Object get(String key) {
        return toMap().get(key);
    }

    /**
     * These settings with {@code update} applied (JSON-like values); {@link InvalidSettingException} on
     * anything unexpected, with nothing applied.
     */
    public AppSettings apply(Map<String, ?> update) {
        Number interval = breakIntervalMin;
        QuietHours quiet = quietHours;
        boolean v = voice;
        String c = clock;
        boolean sounds = uiSounds;
        for (Map.Entry<String, ?> e : update.entrySet()) {
            Object value = e.getValue();
            switch (e.getKey()) {
                case "break_interval_min" -> {
                    if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 1
                            || n.doubleValue() > 240) {
                        throw new InvalidSettingException("break_interval_min must be a number of minutes between 1 and 240");
                    }
                    interval = whole(n.doubleValue());
                }
                case "quiet_hours" -> {
                    if (!(value instanceof Map<?, ?> m)) {
                        throw new InvalidSettingException("quiet_hours must be an object");
                    }
                    boolean enabled = quiet.enabled();
                    String start = quiet.start();
                    String end = quiet.end();
                    for (Map.Entry<?, ?> q : m.entrySet()) {
                        String k = String.valueOf(q.getKey());
                        Object qv = q.getValue();
                        switch (k) {
                            case "enabled" -> {
                                if (!(qv instanceof Boolean b)) {
                                    throw new InvalidSettingException("quiet_hours.enabled must be true or false");
                                }
                                enabled = b;
                            }
                            case "start", "end" -> {
                                parseHhmm(qv);
                                if (!(qv instanceof String s)) {
                                    // Python keeps the value as given when str(v) parses; keep it as text
                                    throw new InvalidSettingException("expected a time HH:MM, got " + PyRepr.of(qv));
                                }
                                if (k.equals("start")) {
                                    start = s;
                                } else {
                                    end = s;
                                }
                            }
                            default -> throw new InvalidSettingException("unknown setting quiet_hours." + k);
                        }
                    }
                    quiet = new QuietHours(enabled, start, end);
                }
                case "voice", "ui_sounds" -> {
                    if (!(value instanceof Boolean b)) {
                        throw new InvalidSettingException(e.getKey() + " must be true or false");
                    }
                    if (e.getKey().equals("voice")) {
                        v = b;
                    } else {
                        sounds = b;
                    }
                }
                case "clock" -> {
                    if (!"24h".equals(value) && !"12h".equals(value)) {
                        throw new InvalidSettingException("clock must be \"24h\" or \"12h\"");
                    }
                    c = (String) value;
                }
                default -> throw new InvalidSettingException("unknown setting " + e.getKey());
            }
        }
        return new AppSettings(interval, quiet, v, c, sounds);
    }

    /** Minutes since midnight of {@code HH:MM}. */
    static int parseHhmm(Object v) {
        String s = String.valueOf(v);
        String[] parts = s.split(":", -1);
        int h;
        int m;
        try {
            if (parts.length != 2) {
                throw new NumberFormatException();
            }
            h = pyInt(parts[0]);
            m = pyInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new InvalidSettingException("expected a time HH:MM, got " + PyRepr.of(v));
        }
        if (h < 0 || h >= 24 || m < 0 || m >= 60) {
            throw new InvalidSettingException("expected a time HH:MM, got " + PyRepr.of(v));
        }
        return h * 60 + m;
    }

    /** Python's {@code int(str)}: surrounding blanks and one sign allowed, digits and underscores. */
    private static int pyInt(String s) {
        String t = s.strip();
        if (t.isEmpty() || !t.matches("[+-]?\\d+(_\\d+)*")) {
            throw new NumberFormatException(s);
        }
        return Integer.parseInt(t.replace("_", ""));
    }

    private static Number whole(double v) {
        return v == Math.rint(v) && Math.abs(v) < Integer.MAX_VALUE ? (Number) (int) v : (Number) v;
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.LinkedHashMap;
import java.util.Map;

import marvin.host.application.face.port.in.FaceImage;
import marvin.host.application.presence.port.in.PresenceHistory;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.settings.port.in.ManageSettings;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.presence.history.Words;
import marvin.host.domain.settings.AppSettings;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.shared.PyNumbers;

/** The live state at the top of the app (the Python {@code UIServer.snapshot()}), from the contexts' ports. */
public final class LiveState {
    private final PresenceQuery presence;
    private final PresenceHistory history;
    private final ManageSettings settings;
    private final FaceImage face;
    private final Clocks clocks;
    private final LocalDays days;

    public LiveState(PresenceQuery presence, PresenceHistory history, ManageSettings settings, FaceImage face,
                     Clocks clocks, LocalDays days) {
        this.presence = presence;
        this.history = history;
        this.settings = settings;
        this.face = face;
        this.clocks = clocks;
        this.days = days;
    }

    public Map<String, Object> snapshot() {
        PresenceState s = presence.state();
        AppSettings cfg = settings.current();
        double now = clocks.wallSeconds();
        double interval = cfg.breakIntervalS();
        double seatedS = s.seated() ? s.seatedS() : 0.0;
        boolean online = history.online();
        Words.Status st = Words.status(online, s.present(), s.seated(), seatedS, history.awaySeconds(), s.breathRate(),
                s.heartRate(), interval, history.everOnline());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", now);
        m.put("online", online);
        m.put("present", s.present());
        m.put("seated", s.seated());
        m.put("seated_s", PyNumbers.round(seatedS, 1));
        m.put("still_s", PyNumbers.round(s.stillS(), 1));
        m.put("distance_m", s.distanceM() == null ? null : PyNumbers.round(s.distanceM(), 2));
        m.put("breath_rate", s.breathRate() == null ? null : PyNumbers.round(s.breathRate(), 1));
        m.put("heart_rate", s.heartRate() == null ? null : PyNumbers.round(s.heartRate(), 1));
        m.put("targets", s.targets());
        m.put("vitals_sensor", s.vitalsSensor());
        // the robot said its sensor data is simulated (HELLO flag)
        m.put("simulated", s.simulated());
        m.put("expression", face.expression());
        m.put("status", st.text());
        m.put("detail", st.detail());
        m.put("mood", st.mood());
        m.put("quiet", cfg.quietHours().contains(days.minuteOfDay(now)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("interval_s", interval);
        b.put("seated_s", PyNumbers.round(seatedS, 1));
        b.put("progress", interval > 0 ? PyNumbers.round(Math.min(1.0, seatedS / interval), 3) : 0.0);
        b.put("due", s.seated() && seatedS >= interval);
        m.put("break", b);
        return m;
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.LinkedHashMap;
import java.util.Map;

import marvin.host.application.face.port.in.FaceImage;
import marvin.host.application.presence.port.in.PresenceHistory;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.settings.port.in.ManageSettings;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.presence.history.Words;
import marvin.host.domain.robot.event.RoomState;
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
    private final RobotLinkQuery robot;

    public LiveState(PresenceQuery presence, PresenceHistory history, ManageSettings settings, FaceImage face,
                     Clocks clocks, LocalDays days, RobotLinkQuery robot) {
        this.presence = presence;
        this.history = history;
        this.settings = settings;
        this.face = face;
        this.clocks = clocks;
        this.days = days;
        this.robot = robot;
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
        // the room sensors' latest readings (room.py), null before any
        m.put("room", room(robot.room()));
        return m;
    }

    /** The app's {@code room} (the Python {@code Room.snapshot()}): {@code null} before any reading. */
    static Map<String, Object> room(RoomState r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fresh", r.fresh());
        m.put("age_s", PyNumbers.round(r.ageS(), 1));
        m.put("device", r.device());
        m.put("simulated", r.simulated());
        m.put("temperature_c", r.temperatureC() == null ? null : PyNumbers.round(r.temperatureC(), 1));
        m.put("humidity", r.humidity() == null ? null : PyNumbers.round(r.humidity(), 1));
        m.put("light", r.light() == null ? null : PyNumbers.round(r.light(), 3));
        m.put("light_word", r.lightWord());
        m.put("motion_sensor", r.motionSensor());
        m.put("climate_sensor", r.climateSensor());
        m.put("light_sensor", r.lightSensor());
        m.put("warming_up", r.warmingUp());
        m.put("motion", r.motion());
        m.put("motion_age_s", r.motionAgeS() == null ? null : PyNumbers.round(r.motionAgeS(), 1));
        return m;
    }
}

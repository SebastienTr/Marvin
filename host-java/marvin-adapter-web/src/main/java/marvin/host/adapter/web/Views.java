// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.presence.history.DayStats;
import marvin.host.domain.presence.history.Interval;
import marvin.host.domain.presence.history.StoredEvent;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.robot.SensorScene;
import marvin.host.domain.system.LogEntry;

/**
 * The app's JSON, key for key as the Python host sends it ({@code ui/server.py}, {@code stats.py},
 * {@code sink.py}): maps in the Python key order, integers where Python has integers.
 */
public final class Views {
    private Views() {
    }

    static Map<String, Object> day(DayStats d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.date().toString());
        m.put("start", d.start());
        m.put("end", d.end());
        m.put("now", d.now());
        m.put("present_s", d.presentS());
        m.put("seated_s", d.seatedS());
        m.put("sessions", d.sessions());
        m.put("breaks", d.breaks());
        m.put("break_s", d.breakS());
        m.put("longest_s", d.longestS());
        m.put("reminders", d.reminders());
        m.put("first_arrival", d.firstArrival());
        m.put("last_departure", d.lastDeparture());
        m.put("breath_rate", d.breathRate());
        m.put("heart_rate", d.heartRate());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("present", intervals(d.timeline().present()));
        t.put("seated", intervals(d.timeline().seated()));
        t.put("breaks", intervals(d.timeline().breaks()));
        t.put("reminders", d.timeline().reminders());
        m.put("timeline", t);
        return m;
    }

    private static List<List<Double>> intervals(List<Interval> intervals) {
        return intervals.stream().map(i -> List.of(i.start(), i.end())).toList();
    }

    static Map<String, Object> summary(DayStats.DaySummary d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.date().toString());
        m.put("seated_s", d.seatedS());
        m.put("present_s", d.presentS());
        m.put("sessions", d.sessions());
        m.put("breaks", d.breaks());
        m.put("longest_s", d.longestS());
        return m;
    }

    static Map<String, Object> event(StoredEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("ts", e.ts());
        m.put("kind", e.kind());
        m.put("detail", e.detail());
        m.put("data", e.data());
        m.put("text", e.text());
        return m;
    }

    static Map<String, Object> log(LogEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("ts", e.ts());
        m.put("source", e.source());
        m.put("level", e.level());
        m.put("text", e.text());
        m.putAll(e.extra());
        return m;
    }

    static Map<String, Object> conversation(ConversationEntry e) {
        return e.toMap();
    }

    /** The devices as the app lists them. */
    public static Object devicesOf(marvin.host.application.robot.port.in.RobotLinkQuery robot) {
        return devices(robot.devices());
    }

    static List<Map<String, Object>> devices(List<DeviceStatus> devices) {
        List<Map<String, Object>> out = new ArrayList<>(devices.size());
        for (DeviceStatus d : devices) {
            out.add(device(d));
        }
        return out;
    }

    static Map<String, Object> device(DeviceStatus d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("name", d.name());
        m.put("role", d.role().wireName());
        m.put("label", d.role().label());
        m.put("board", d.board());
        m.put("firmware", d.firmware());
        m.put("ip", d.ip());
        m.put("rssi", d.rssi());
        m.put("rssi_bars", d.rssiBars());
        m.put("uptime_s", d.uptimeS());
        m.put("simulated", d.simulated());
        m.put("camera", d.camera());
        m.put("audio", d.audio());
        m.put("online", d.online());
        m.put("age_s", d.ageS());
        m.put("connected_at", d.connectedAt());
        m.put("rates", d.rates());
        m.put("loss_pct", d.lossPct());
        m.put("datagrams", d.stats().datagrams());
        m.put("lost", d.stats().lost());
        m.put("crc_errors", d.stats().crcErrors());
        m.put("bad", d.stats().bad());
        m.put("shed", d.stats().shedTotal());
        m.put("points", d.points());
        return m;
    }

    static Map<String, Object> scene(SensorScene.View v) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (v.lidar() == null) {
            m.put("lidar", null);
        } else {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("age_s", v.lidar().ageS());
            l.put("ranges_cm", v.lidar().rangesCm());
            l.put("points", v.lidar().points());
            m.put("lidar", l);
        }
        m.put("targets", v.targets());
        List<List<Object>> trail = new ArrayList<>(v.trail().size());
        for (double[] p : v.trail()) {
            trail.add(List.of(p[0], (long) p[1], (long) p[2]));
        }
        m.put("trail", trail);
        if (v.vitals() == null) {
            m.put("vitals", null);
        } else {
            SensorScene.VitalsView x = v.vitals();
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("valid", x.valid());
            vm.put("breath_rate", x.breathRate());
            vm.put("heart_rate", x.heartRate());
            vm.put("distance_cm", x.distanceCm());
            vm.put("waves", x.waves());
            if (x.rates() != null) {
                List<List<Object>> rates = new ArrayList<>(x.rates().size());
                for (Double[] r : x.rates()) {
                    List<Object> row = new ArrayList<>(3);
                    row.add(r[0].longValue());
                    row.add(r[1]);
                    row.add(r[2]);
                    rates.add(row);
                }
                vm.put("rates", rates);
            }
            m.put("vitals", vm);
        }
        Map<String, Object> radar = new LinkedHashMap<>();
        radar.put("half_angle_deg", SensorScene.RADAR_HALF_ANGLE_DEG);
        radar.put("range_cm", SensorScene.RADAR_RANGE_CM);
        m.put("radar", radar);
        return m;
    }
}

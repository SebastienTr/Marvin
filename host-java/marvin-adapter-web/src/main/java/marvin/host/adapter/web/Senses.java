// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.VoiceSnapshot;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.event.RoomState;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.shared.PyNumbers;

/**
 * The Senses screen's views (docs/ui.md, "Senses"): what Marvin feels (presence and where it comes from, the room, the
 * vital signs), hears (the voice's state, or why it is off) and thinks (the model, the last answer's stages), from the
 * contexts' ports. Pure functions of what the ports report: nothing is stored.
 */
final class Senses {
    private Senses() {
    }

    // ------------------------------------------------------------------ feels

    /** Which sensors the devices online send now. */
    record Sensors(boolean lidar, boolean radar, boolean vitals, boolean pir, boolean climate, boolean light) {
        static Sensors of(List<DeviceStatus> devices, RoomState room) {
            boolean lidar = false;
            boolean radar = false;
            boolean vitals = false;
            for (DeviceStatus d : devices) {
                if (!d.online()) {
                    continue;
                }
                lidar |= rate(d, "scans") > 0;
                radar |= rate(d, "radar") > 0;
                vitals |= rate(d, "vitals") > 0;
            }
            boolean fresh = room != null && room.fresh();
            return new Sensors(lidar, radar, vitals, fresh && room.motionSensor(), fresh && room.climateSensor(),
                    fresh && room.lightSensor());
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lidar", lidar);
            m.put("radar", radar);
            m.put("vitals", vitals);
            m.put("pir", pir);
            m.put("climate", climate);
            m.put("light", light);
            m.put("camera", false);             // the camera only looks when asked: never for presence
            return m;
        }
    }

    static double rate(DeviceStatus d, String key) {
        Double r = d.rates() == null ? null : d.rates().get(key);
        return r == null ? 0.0 : r;
    }

    /**
     * Where presence comes from: {@code radar} (an LD2450 or MR60BHA2: a position, stillness, sitting), {@code pir}
     * (motion only: no position), or {@code none}.
     */
    static String presenceSource(PresenceState s, Sensors k) {
        if (s.motionPresence()) {
            return "pir";
        }
        if (k.radar() || k.vitals() || s.targets() > 0 || s.vitalsSensor()) {
            return "radar";
        }
        return k.pir() ? "pir" : "none";
    }

    /** What the brain's belief rests on, one short line per piece of evidence, in its order of trust. */
    static List<String> reasoning(PresenceState s, String source, Sensors k, RoomState room) {
        List<String> out = new ArrayList<>();
        switch (source) {
            case "radar" -> {
                if (s.present() && s.distanceM() != null) {
                    out.add(String.format(Locale.ROOT, "Present: the radar sees %s, the nearest at %.2f m.",
                            s.targets() <= 1 ? "one person" : s.targets() + " people", s.distanceM()));
                } else if (s.present()) {
                    out.add("Present: the vital-sign radar reads someone in front of it.");
                } else {
                    out.add("Nobody: the radar sees no one in its field of view.");
                }
                if (s.present()) {
                    out.add(s.seated()
                            ? String.format(Locale.ROOT, "Seated for %s: still for %.0f s.", minutes(s.seatedS()), s.stillS())
                            : String.format(Locale.ROOT, "Not seated: moving (%.0f cm/s), still for %.0f s.",
                                    Math.abs(s.speedCms()), s.stillS()));
                }
            }
            case "pir" -> {
                Double ago = room == null ? null : room.motionAgeS();
                out.add(s.present()
                        ? "Present from motion alone: the PIR saw movement" + (ago == null ? "." : String.format(Locale.ROOT,
                                " %.0f s ago.", ago))
                        : "Nobody: no movement for a while (PIR).");
                out.add("A motion sensor gives no position and cannot tell sitting still from leaving.");
            }
            default -> out.add(k.lidar()
                    ? "No presence sensor: the lidar sees shapes, but cannot tell a person from furniture."
                    : "No presence sensor connected: Marvin cannot tell whether someone is here.");
        }
        if (s.vitalsSensor() || k.vitals()) {
            out.add(s.breathRate() != null || s.heartRate() != null
                    ? String.format(Locale.ROOT, "Vital signs read by the MR60BHA2: %s breaths/min, %s beats/min.",
                            num(s.breathRate()), num(s.heartRate()))
                    : "The MR60BHA2 is connected but cannot read vital signs now (too far, or moving).");
        }
        out.add("The camera is never used for presence: it takes a picture only when asked.");
        if (s.simulated()) {
            out.add("The robot says this data is simulated.");
        }
        return out;
    }

    static Map<String, Object> feels(PresenceState s, List<DeviceStatus> devices, RoomState room) {
        Sensors k = Sensors.of(devices, room);
        String source = presenceSource(s, k);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("present", s.present());
        p.put("seated", s.seated());
        p.put("source", source);
        p.put("distance_m", s.distanceM() == null ? null : PyNumbers.round(s.distanceM(), 2));
        p.put("still_s", PyNumbers.round(s.stillS(), 1));
        p.put("seated_s", s.seated() ? PyNumbers.round(s.seatedS(), 1) : 0.0);
        p.put("targets", s.targets());
        p.put("simulated", s.simulated());
        p.put("reasoning", reasoning(s, source, k, room));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sensor", s.vitalsSensor() || k.vitals());
        v.put("breath_rate", s.breathRate() == null ? null : PyNumbers.round(s.breathRate(), 1));
        v.put("heart_rate", s.heartRate() == null ? null : PyNumbers.round(s.heartRate(), 1));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sensors", k.toMap());
        m.put("presence", p);
        m.put("vitals", v);
        m.put("room", LiveState.room(room));
        return m;
    }

    private static String minutes(double s) {
        return s < 60 ? Math.round(s) + " s" : Math.round(s / 60) + " min";
    }

    private static String num(Double x) {
        return x == null ? "–" : String.valueOf(Math.round(x));
    }

    // ------------------------------------------------------------------ hears

    /**
     * The voice as the ears see it: its state, the engines, the language, the device, the last microphone level, and
     * {@code why} it hears nothing when it does not run (empty when it runs).
     */
    static Map<String, Object> hears(boolean available, String unavailable, VoiceSnapshot v, Map<String, Object> hearing,
                                     Map<String, Object> settings, boolean recent) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean running = available && "on".equals(v.state()) && !"off".equals(v.status());
        m.put("state", available ? v.state() : "unavailable");
        m.put("status", running ? v.status() : "off");
        m.put("muted", v.muted());
        m.put("why", why(available, unavailable, v));
        m.put("wake", v.wake());
        m.put("language", settings.get("language") == null ? "auto" : settings.get("language"));
        m.put("vocabulary", settings.getOrDefault("vocabulary", List.of()));
        m.put("known_voices_only", Boolean.TRUE.equals(settings.get("known_voices_only")));
        m.put("stt", hearing.getOrDefault("stt", ""));
        m.put("stt_setting", settings.getOrDefault("stt", "auto"));
        m.put("tts", hearing.getOrDefault("tts", ""));
        m.put("tts_setting", settings.getOrDefault("tts", "auto"));
        m.put("tts_voice", settings.get("tts_voice"));
        m.put("device", v.device());
        m.put("earcons", v.earcons());
        m.put("level", hearing.get("level"));
        if (recent) {
            m.put("recent", hearing.getOrDefault("recent", List.of()));
        }
        return m;
    }

    static String why(boolean available, String unavailable, VoiceSnapshot v) {
        if (!available) {
            return "No voice on this host: " + unavailable;
        }
        return switch (v.state()) {
            case "on" -> v.muted() ? "Muted: the microphone is closed until you unmute it." : "";
            case "starting" -> "The voice is starting: loading speech recognition and synthesis.";
            case "stopping" -> "The voice is stopping.";
            case "error" -> "The voice stopped: " + v.error() + (v.fix() == null || v.fix().isEmpty() ? "" : ". " + v.fix());
            default -> "The voice is off: turn it on in Talk.";
        };
    }

    // ------------------------------------------------------------------ thinks

    /** The last answer: when, its model, the time to its first word and each stage (seconds), the model's loading. */
    static Map<String, Object> lastAnswer(List<ConversationEntry> recent) {
        for (int i = recent.size() - 1; i >= 0; i--) {
            ConversationEntry e = recent.get(i);
            if (!"reply".equals(e.kind())) {
                continue;
            }
            Map<String, Object> d = e.data() == null ? Map.of() : e.data();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", e.t());
            m.put("model", d.get("model"));
            m.put("first_word_s", d.get("first_word_s"));
            m.put("latency", d.getOrDefault("latency", Map.of()));
            m.put("interrupted", Boolean.TRUE.equals(d.get("interrupted")));
            m.put("load_s", d.get("memory") instanceof Map<?, ?> mem && mem.get("usage") instanceof Map<?, ?> u
                    ? u.get("load_s") : null);
            return m;
        }
        return null;
    }

    /** What the vision model said last: the last {@code look} tool call of an answer, or {@code null}. */
    static Map<String, Object> lastLook(List<ConversationEntry> recent) {
        for (int i = recent.size() - 1; i >= 0; i--) {
            ConversationEntry e = recent.get(i);
            if (!"reply".equals(e.kind()) || e.data() == null || !(e.data().get("tools") instanceof List<?> tools)) {
                continue;
            }
            for (int j = tools.size() - 1; j >= 0; j--) {
                if (tools.get(j) instanceof Map<?, ?> call && "look".equals(call.get("name"))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("t", e.t());
                    m.put("ok", Boolean.TRUE.equals(call.get("ok")));
                    m.put("text", call.get("ok") == Boolean.TRUE ? call.get("result") : call.get("error"));
                    m.put("seconds", call.get("seconds"));
                    m.put("answer", e.text());
                    return m;
                }
            }
        }
        return null;
    }
}

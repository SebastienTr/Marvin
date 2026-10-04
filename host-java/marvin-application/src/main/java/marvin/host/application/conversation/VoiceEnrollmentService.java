// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.in.VoiceEnrollment;
import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.port.out.VoiceprintStore;
import marvin.host.domain.conversation.AudioDeviceProfile;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.Voiceprint;
import marvin.host.domain.shared.Clocks;

/**
 * The voice calibration (docs/voice.md, "Your voice"). The app starts it; the voice sidecar measures the device and
 * the person's voice ({@link VoiceSidecar.Enroll}, {@link VoiceSidecar.EnrollProgress}); when it is done, this keeps
 * what it measured: the device's thresholds in {@code voice.json} ({@code devices.computer} or {@code devices.robot}:
 * {@code vad_min_dbfs}, {@code speech_min_dbfs}, {@code vad_threshold} and a {@code calibration} record) and the
 * person's voiceprint in the {@link VoiceprintStore}; then the voice is given its settings again.
 */
public final class VoiceEnrollmentService implements VoiceEnrollment {
    private static final Logger log = Logger.getLogger("marvin.voice");

    /**
     * What the person reads: French and English, the name in the first one, Marvin's name in each (the owner speaks
     * both, often in one sentence).
     */
    public static final List<String> SENTENCES = List.of(
            "Bonjour Marvin, je m'appelle {name}.",
            "Hey Marvin, what's the weather like today?",
            "Marvin, rappelle-moi d'arroser les plantes demain matin.");
    static final double NOISE_S = 1.5;

    private final VoiceService voice;
    private final VoiceSettingsStore settings;
    private final VoiceprintStore prints;
    private final Clocks clocks;
    private final Object lock = new Object();
    private Map<String, Object> state = idle();
    private String name = "";

    public VoiceEnrollmentService(VoiceService voice, VoiceSettingsStore settings, VoiceprintStore prints, Clocks clocks) {
        this.voice = Objects.requireNonNull(voice, "voice");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.prints = Objects.requireNonNull(prints, "prints");
        this.clocks = Objects.requireNonNull(clocks, "clocks");
    }

    private static Map<String, Object> idle() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", "idle");
        return m;
    }

    @Override
    public Map<String, Object> state() {
        Map<String, Object> m;
        synchronized (lock) {
            m = new LinkedHashMap<>(state);
        }
        m.putIfAbsent("index", 0);
        m.putIfAbsent("heard", List.of());
        m.putIfAbsent("message", "");
        m.putIfAbsent("result", null);
        m.put("name", name);
        m.put("sentences", sentences(name));
        List<Map<String, Object>> people = prints.load().stream().map(Voiceprint::describe).toList();
        m.put("people", people);
        Map<String, Object> calibration = calibrations();
        m.put("calibration", calibration);
        m.put("first_run", people.isEmpty() && calibration.isEmpty());
        return m;
    }

    static List<String> sentences(String name) {
        String n = name == null || name.isBlank() ? "…" : name;
        return SENTENCES.stream().map(s -> s.replace("{name}", n)).toList();
    }

    /** Each device's last calibration, from voice.json ({@code devices.<kind>.calibration}). */
    private Map<String, Object> calibrations() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (settings.load().get("devices") instanceof Map<?, ?> devices) {
            for (String kind : List.of(AudioDeviceProfile.COMPUTER, AudioDeviceProfile.ROBOT)) {
                if (devices.get(kind) instanceof Map<?, ?> d && d.get("calibration") instanceof Map<?, ?> c) {
                    out.put(kind, c);
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> start(Object who) {
        String n = Voiceprint.checkName(who);
        List<Voiceprint> known = prints.load();
        if (known.size() >= Voiceprint.MAX_PEOPLE && known.stream().noneMatch(v -> same(v.name(), n))) {
            throw new VoiceSettings.InvalidVoiceSettingException("Marvin knows " + Voiceprint.MAX_PEOPLE
                    + " voices already: delete one first");
        }
        synchronized (lock) {
            name = n;
            state = idle();
            state.put("state", "starting");
        }
        if (!voice.enroll(new VoiceSidecar.Enroll("start", n, SENTENCES, NOISE_S))) {
            synchronized (lock) {
                state = idle();
            }
            throw new VoiceControl.VoiceOff("Turn Marvin's voice on first: the calibration listens through it.");
        }
        log.info("voice calibration started for " + n);
        return state();
    }

    @Override
    public Map<String, Object> cancel() {
        voice.enroll(new VoiceSidecar.Enroll("cancel", "", List.of(), 0));
        synchronized (lock) {
            if (!"done".equals(state.get("state"))) {
                state = idle();
                state.put("state", "cancelled");
            }
        }
        return state();
    }

    @Override
    public Map<String, Object> forget(Object who) {
        String n = who instanceof String s ? s.strip() : "";
        List<Voiceprint> known = prints.load();
        List<Voiceprint> kept = known.stream().filter(v -> !same(v.name(), n)).toList();
        if (kept.size() == known.size()) {
            throw new VoiceSettings.InvalidVoiceSettingException("Marvin knows no voice called " + n);
        }
        prints.save(kept);
        log.info("voiceprint of " + n + " deleted");
        voice.reload("Voiceprint of " + n + " deleted");
        return state();
    }

    private static boolean same(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    /** The voice's progress ({@link VoiceService#setEnrollment}). */
    public void onProgress(VoiceSidecar.EnrollProgress p) {
        synchronized (lock) {
            Map<String, Object> m = new LinkedHashMap<>(state);
            m.put("state", p.state());
            switch (p.state()) {
                case "noise" -> m.put("seconds", p.seconds());
                case "sentence" -> {
                    m.put("index", p.index());
                    m.put("message", "");
                }
                case "heard" -> {
                    List<Object> heard = m.get("heard") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("index", p.index());
                    h.put("text", p.text());
                    h.put("match", p.match());
                    h.put("dbfs", p.dbfs());
                    heard.add(h);
                    m.put("heard", heard);
                }
                case "retry", "failed" -> m.put("message", p.message());
                case "done" -> m.put("result", p.result() == null ? null : keep(p.result()));
                default -> {
                }
            }
            state = m;
        }
        if ("done".equals(p.state()) && p.result() != null) {
            voice.reload("Voice calibrated" + (p.result().voiceprint() != null ? ": Marvin knows "
                    + p.result().name() + "'s voice" : ""));
        }
    }

    /** Stores what the calibration measured; returns what the app shows of it. */
    private Map<String, Object> keep(VoiceSidecar.EnrollResult r) {
        String kind = AudioDeviceProfile.ROBOT.equals(r.kind()) ? AudioDeviceProfile.ROBOT : AudioDeviceProfile.COMPUTER;
        String at = LocalDateTime.ofInstant(Instant.ofEpochMilli((long) (clocks.wallSeconds() * 1000)),
                ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS).toString();
        Map<String, Object> calibration = new LinkedHashMap<>();
        calibration.put("at", at);
        calibration.put("name", r.name());
        calibration.put("noise_dbfs", r.noiseDbfs());
        calibration.put("speech_dbfs", r.speechDbfs());
        calibration.put("warnings", r.warnings());
        Map<String, Object> devices = new LinkedHashMap<>();
        if (settings.load().get("devices") instanceof Map<?, ?> d) {
            d.forEach((k, v) -> devices.put(String.valueOf(k), v));
        }
        Map<String, Object> device = new LinkedHashMap<>();
        if (devices.get(kind) instanceof Map<?, ?> d) {
            d.forEach((k, v) -> device.put(String.valueOf(k), v));
        }
        device.put("vad_min_dbfs", r.vadMinDbfs());
        device.put("speech_min_dbfs", r.speechMinDbfs());
        device.put("vad_threshold", r.vadThreshold());
        device.put("calibration", calibration);
        devices.put(kind, device);
        settings.save(Map.of("devices", devices));
        boolean enrolled = false;
        if (r.voiceprint() != null && !r.name().isBlank()) {
            Voiceprint v = r.voiceprint();
            List<Voiceprint> people = new ArrayList<>(prints.load().stream().filter(p -> !same(p.name(), r.name())).toList());
            people.add(new Voiceprint(r.name(), v.embedding(), v.model(), at, kind));
            prints.save(people);
            enrolled = true;
        }
        log.info("voice calibrated on the " + kind + ": noise " + r.noiseDbfs() + " dBFS, voice " + r.speechDbfs()
                + " dBFS" + (enrolled ? "; voiceprint of " + r.name() + " kept" : ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("device", kind);
        out.put("noise_dbfs", r.noiseDbfs());
        out.put("speech_dbfs", r.speechDbfs());
        out.put("vad_min_dbfs", r.vadMinDbfs());
        out.put("speech_min_dbfs", r.speechMinDbfs());
        out.put("vad_threshold", r.vadThreshold());
        out.put("warnings", r.warnings());
        out.put("transcripts", r.transcripts());
        out.put("voiceprint", enrolled);
        return out;
    }
}

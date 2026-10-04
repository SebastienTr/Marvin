// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A person's voice as Marvin knows it (docs/voice.md, "Your voice"): the mean speaker embedding of the sentences they
 * read at enrollment, under their name. Kept on this computer only ({@code voiceprints.json} beside {@code voice.json}),
 * sent to the voice with its settings, deleted from the app.
 *
 * @param name      whose voice
 * @param embedding the vector (unit length), as the voice computed it
 * @param model     the speaker model it was made with: a print of another model is not compared
 * @param created   when it was made (ISO local date-time)
 * @param device    the device it was recorded on ({@code computer} or {@code robot})
 */
public record Voiceprint(String name, List<Double> embedding, String model, String created, String device) {

    public static final int MAX_NAME = 40;
    public static final int MAX_PEOPLE = 8;

    public Voiceprint {
        name = name == null ? "" : name.strip();
        embedding = embedding == null ? List.of() : List.copyOf(embedding);
        model = model == null ? "" : model;
        created = created == null ? "" : created;
        device = device == null ? "" : device;
    }

    /** What the app shows: everything but the vector. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("created", created);
        m.put("device", device);
        m.put("model", model);
        return m;
    }

    /** {@code voiceprints.json}'s entry. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = describe();
        m.put("embedding", embedding);
        return m;
    }

    /** An entry of {@code voiceprints.json}, or {@code null} when it is not one. */
    public static Voiceprint fromMap(Object o) {
        if (!(o instanceof Map<?, ?> m) || !(m.get("name") instanceof String n) || n.isBlank()
                || !(m.get("embedding") instanceof List<?> e) || e.isEmpty()) {
            return null;
        }
        List<Double> v = e.stream().filter(Number.class::isInstance).map(x -> ((Number) x).doubleValue()).toList();
        if (v.size() != e.size()) {
            return null;
        }
        return new Voiceprint(n, v, str(m.get("model")), str(m.get("created")), str(m.get("device")));
    }

    /** A name the app may enrol: printable, at most {@link #MAX_NAME} characters. */
    public static String checkName(Object name) {
        if (!(name instanceof String s) || s.isBlank()) {
            throw new VoiceSettings.InvalidVoiceSettingException("name must be the person's name");
        }
        String t = String.join(" ", s.strip().split("\\s+"));
        if (t.codePointCount(0, t.length()) > MAX_NAME || !VoiceSettings.printable(t)
                || t.chars().anyMatch(c -> "{}<>[]\\\"`".indexOf(c) >= 0)) {
            throw new VoiceSettings.InvalidVoiceSettingException("name must be a name of at most " + MAX_NAME + " characters");
        }
        return t;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }
}

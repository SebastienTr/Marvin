// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The voice's settings: the keys of {@code voice.json} (the file the Python host's {@code marvin-host talk}
 * and {@code run} read), the ones the app edits and how they are checked (the Python host's
 * {@code voice/control.py}), and the configuration they give.
 */
public final class VoiceSettings {

    public static final List<String> STT_CHOICES = List.of("auto", "mlx", "faster-whisper");
    public static final List<String> TTS_CHOICES = List.of("auto", "say", "piper", "espeak");
    public static final List<String> STT_MODELS = List.of("tiny", "base", "small", "medium", "large-v3", "turbo");
    /** Where the voice hears and speaks: the computer, the robot (its microphone and speaker), or the robot when one with audio is connected. */
    public static final List<String> AUDIO_ROUTES = List.of("computer", "robot", "auto");
    /** The settings the app edits. */
    public static final List<String> APP_KEYS = List.of("llm_model", "stt", "stt_model", "tts", "tts_voice", "language",
            "wake", "follow_up_s", "reminders", "welcome_back", "tools", "internet", "home_place");
    /** Every key of voice.json. */
    public static final List<String> FILE_KEYS = List.of("stt", "stt_model", "llm_model", "ollama_host", "tts",
            "tts_voice", "language", "default_language", "wake", "duplex", "echo_tail_s", "follow_up_s",
            "listen_window_s", "speculative_stt", "end_silence_ms", "reminders", "welcome_back", "tools", "internet",
            "home_place", "chime", "audio_route", "input_device", "output_device", "continue_grace_s",
            "end_silence_long_ms");
    public static final int MAX_HOME_PLACE = 80;
    public static final String DEFAULT_MODEL = "qwen3:4b-instruct";
    public static final String DEFAULT_OLLAMA = "http://localhost:11434";

    private static final Pattern MODEL = Pattern.compile("[\\w.:/@+-]{1,120}", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern STT_MODEL = Pattern.compile("[\\w.:/@+-]{1,160}", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private VoiceSettings() {
    }

    /** A setting the app sent that is not acceptable; the message is shown as is. */
    public static final class InvalidVoiceSettingException extends IllegalArgumentException {
        public InvalidVoiceSettingException(String message) {
            super(message);
        }
    }

    /**
     * The app's voice settings, checked (control.py {@code validate}). {@code "auto"} (language, speech
     * recognition model) and {@code ""} (voice) mean the default and become {@code null}.
     */
    public static Map<String, Object> validate(Map<String, ?> update) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : update.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            switch (key) {
                case "llm_model" -> {
                    if (!(v instanceof String s) || !MODEL.matcher(s).matches()) {
                        throw bad("llm_model must be an Ollama model name, e.g. qwen3:4b-instruct");
                    }
                }
                case "stt" -> {
                    if (!STT_CHOICES.contains(v)) {
                        throw bad("stt must be one of " + String.join(", ", STT_CHOICES));
                    }
                }
                case "stt_model" -> {
                    v = v == null || "".equals(v) || "auto".equals(v) ? null : v;
                    if (v != null && (!(v instanceof String s) || !STT_MODEL.matcher(s).matches())) {
                        throw bad("stt_model must be a Whisper model name (tiny, small, turbo...) or auto");
                    }
                }
                case "tts" -> {
                    if (!TTS_CHOICES.contains(v)) {
                        throw bad("tts must be one of " + String.join(", ", TTS_CHOICES));
                    }
                }
                case "tts_voice" -> {
                    v = v == null || "".equals(v) ? null : v;
                    if (v != null && (!(v instanceof String s) || s.codePointCount(0, s.length()) > 160 || !printable(s))) {
                        throw bad("tts_voice must be a voice name");
                    }
                }
                case "language" -> {
                    v = "auto".equals(v) || "".equals(v) ? null : v;
                    if (v != null && !"fr".equals(v) && !"en".equals(v)) {
                        throw bad("language must be \"auto\", \"fr\" or \"en\"");
                    }
                }
                case "home_place" -> {
                    v = v == null ? "" : v;
                    if (!(v instanceof String s)) {
                        throw bad("home_place must be a place name, e.g. Nice");
                    }
                    String t = String.join(" ", SPACES.split(s.strip()));
                    if (t.codePointCount(0, t.length()) > MAX_HOME_PLACE || !printable(t) || t.chars().anyMatch(c -> "{}<>[]\\\"`".indexOf(c) >= 0)) {
                        throw bad("home_place must be a place name of at most " + MAX_HOME_PLACE + " characters, e.g. Nice");
                    }
                    v = t;
                }
                case "wake", "reminders", "welcome_back", "tools", "internet" -> {
                    if (!(v instanceof Boolean)) {
                        throw bad(key + " must be true or false");
                    }
                }
                case "follow_up_s" -> {
                    if (!(v instanceof Number n) || !(n.doubleValue() >= 0 && n.doubleValue() <= 30)) {
                        throw bad("follow_up_s must be a number of seconds between 0 and 30");
                    }
                    v = n.doubleValue();
                }
                default -> throw bad("unknown voice setting " + key);
            }
            out.put(key, v);
        }
        return out;
    }

    private static InvalidVoiceSettingException bad(String message) {
        return new InvalidVoiceSettingException(message);
    }

    /** Python's {@code str.isprintable()}. */
    static boolean printable(String s) {
        return s.codePoints().allMatch(c -> {
            if (c == ' ') {
                return true;
            }
            int t = Character.getType(c);
            return !(t == Character.CONTROL || t == Character.FORMAT || t == Character.PRIVATE_USE
                    || t == Character.SURROGATE || t == Character.UNASSIGNED || t == Character.SPACE_SEPARATOR
                    || t == Character.LINE_SEPARATOR || t == Character.PARAGRAPH_SEPARATOR);
        });
    }

    /** The settings the app edits, with the defaults filled in, in the Python host's order. */
    public static Map<String, Object> appSettings(Map<String, Object> settings) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("llm_model", DEFAULT_MODEL);
        out.put("stt", "auto");
        out.put("stt_model", null);
        out.put("tts", "auto");
        out.put("tts_voice", null);
        out.put("language", null);
        out.put("wake", true);
        out.put("follow_up_s", 5.0);
        out.put("tools", true);
        out.put("internet", true);
        out.put("home_place", "");
        out.put("reminders", true);
        out.put("welcome_back", false);
        for (String k : APP_KEYS) {
            if (settings.containsKey(k)) {
                out.put(k, settings.get(k));
            }
        }
        return out;
    }

    /** The configuration {@code settings} (voice.json's keys) give, as the Python host reads them. */
    public static VoiceConfig config(Map<String, Object> settings) {
        String language = str(settings, "language", null);
        if ("auto".equals(language) || "".equals(language)) {
            language = null;
        }
        String defaultLanguage = language != null ? language : str(settings, "default_language", "fr");
        return new VoiceConfig(
                str(settings, "llm_model", DEFAULT_MODEL), str(settings, "ollama_host", DEFAULT_OLLAMA),
                language, defaultLanguage,
                str(settings, "stt", "auto"), str(settings, "stt_model", null),
                str(settings, "tts", "auto"), str(settings, "tts_voice", null),
                bool(settings, "wake", true), bool(settings, "duplex", false),
                num(settings, "echo_tail_s", 0.8), num(settings, "follow_up_s", 5.0),
                num(settings, "listen_window_s", 6.0), bool(settings, "speculative_stt", true),
                num(settings, "end_silence_ms", 0), bool(settings, "chime", true),
                str(settings, "input_device", null), str(settings, "output_device", null),
                bool(settings, "tools", true), bool(settings, "internet", true),
                str(settings, "home_place", "").strip(), bool(settings, "reminders", true),
                bool(settings, "welcome_back", false), route(str(settings, "audio_route", "computer")),
                optionalNum(settings, "continue_grace_s"), optionalNum(settings, "end_silence_long_ms"));
    }

    private static String route(String s) {
        return AUDIO_ROUTES.contains(s) ? s : "computer";
    }

    private static String str(Map<String, Object> m, String k, String dflt) {
        Object v = m.get(k);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> m, String k, boolean dflt) {
        Object v = m.get(k);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return dflt;
    }

    /** A number of the file, or {@code null} when it is missing or not a number (negative: 0). */
    private static Double optionalNum(Map<String, Object> m, String k) {
        double v = num(m, k, Double.NaN);
        return Double.isNaN(v) ? null : Math.max(0.0, v);
    }

    private static double num(Map<String, Object> m, String k, double dflt) {
        Object v = m.get(k);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
        return dflt;
    }

    /**
     * What the voice is built from.
     *
     * @param language      forced language, {@code null} to follow the speaker
     * @param endSilenceMs  0: the sidecar's default
     * @param audioRoute    {@code computer}, {@code robot} or {@code auto}
     * @param continueGraceS speech resuming this soon after a question, before its answer is heard, continues it;
     *                      {@code null}: the sidecar's default, 0: never
     * @param endSilenceLongMs the end-of-question silence when the words so far announce more; {@code null}: the
     *                      sidecar's default, 0: never longer
     */
    public record VoiceConfig(String llmModel, String ollamaHost, String language, String defaultLanguage,
                              String stt, String sttModel, String tts, String ttsVoice, boolean wake, boolean duplex,
                              double echoTailS, double followUpS, double listenWindowS, boolean speculativeStt,
                              double endSilenceMs, boolean chime, String inputDevice, String outputDevice,
                              boolean tools, boolean internet, String homePlace, boolean reminders,
                              boolean welcomeBack, String audioRoute, Double continueGraceS, Double endSilenceLongMs) {

        /** Languages the speaker is expected to use. */
        public List<String> languages() {
            return language != null ? List.of(language) : List.of("fr", "en");
        }

        /** Question/answer pairs kept (then the older half is dropped). */
        public int memoryTurns() {
            return 8;
        }

        /** The conversation is forgotten after this much silence. */
        public double memoryResetS() {
            return 180.0;
        }

        /** Tool calls, answer, calls again...: then the model must answer. */
        public int maxToolRounds() {
            return 3;
        }
    }
}

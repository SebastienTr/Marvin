// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The voice's settings: the keys of {@code voice.json}, the ones the app edits and how they are checked, and the
 * configuration they give.
 */
public final class VoiceSettings {

    /** Speech recognition: Parakeet where it runs (else Whisper), Parakeet, Whisper on the Apple GPU, Whisper on the CPU. */
    public static final List<String> STT_CHOICES = List.of("auto", "parakeet", "mlx", "faster-whisper");
    /** Voice activity: Silero when its model is downloaded (else WebRTC), or one. */
    public static final List<String> VAD_CHOICES = List.of("auto", "silero", "webrtc", "energy");
    /** The end of a question: Smart Turn when its model is downloaded (else silence), or one. */
    public static final List<String> TURN_CHOICES = List.of("auto", "smart-turn", "silence");
    /** Speech synthesis: the best available (Qwen3-TTS where it runs fast enough, else Piper, else say), or one. */
    public static final List<String> TTS_CHOICES = List.of("auto", "qwen", "say", "piper", "espeak");
    /** Qwen3-TTS's voices: Marvin's own, cloned from a reference recording (host/marvin_host/voice/voices/). */
    public static final List<String> QWEN_VOICES = List.of("marvin");
    public static final List<String> STT_MODELS = List.of("tiny", "base", "small", "medium", "large-v3", "turbo");
    /** Where the voice hears and speaks: the computer, the robot (its microphone and speaker), or the robot when one with audio is connected. */
    public static final List<String> AUDIO_ROUTES = List.of("computer", "robot", "auto");
    /**
     * Which camera Marvin looks through with its {@code look} tool: the robot's when one with a camera is connected, else
     * the computer's ({@code auto}); always the computer's or the robot's; or none.
     */
    public static final List<String> CAMERAS = List.of("auto", "computer", "robot", "off");
    /** The settings the app edits. */
    public static final List<String> APP_KEYS = List.of("llm_model", "stt", "stt_model", "tts", "tts_voice", "language",
            "wake", "follow_up_s", "reminders", "welcome_back", "greet_on_sit", "tools", "internet", "home_place",
            "vision_model", "camera", "vocabulary", "known_voices_only");
    /** Every key of voice.json. */
    public static final List<String> FILE_KEYS = List.of("stt", "stt_model", "llm_model", "ollama_host", "tts",
            "tts_voice", "language", "default_language", "wake", "duplex", "echo_tail_s", "follow_up_s",
            "listen_window_s", "speculative_stt", "end_silence_ms", "reminders", "welcome_back", "greet_on_sit", "tools",
            "internet", "home_place", "chime", "audio_route", "input_device", "output_device", "continue_grace_s",
            "end_silence_long_ms", "vision_model", "camera", "continue_join_s", "speculate_after_s", "devices", "vad", "turn",
            "turn_check_ms", "turn_max_ms", "vocabulary", "known_voices_only", "speaker_threshold");
    public static final int MAX_HOME_PLACE = 80;
    public static final String DEFAULT_MODEL = "qwen3:4b-instruct";
    public static final String DEFAULT_OLLAMA = "http://localhost:11434";

    // The voice's defaults: the only place they are written (the voice sidecar is sent every value; its own copies are
    // for its tests, and a test keeps them equal: marvin-contracts golden/voice/configure_defaults.json).
    public static final double FOLLOW_UP_S = 5.0;
    public static final double LISTEN_WINDOW_S = 6.0;
    public static final double SPECULATE_AFTER_S = 0.25;
    public static final double END_SILENCE_MS = 550.0;
    public static final double END_SILENCE_LONG_MS = 1100.0;
    public static final double CONTINUE_GRACE_S = 1.5;
    public static final double CONTINUE_JOIN_S = 6.0;
    public static final double TURN_CHECK_MS = 200.0;
    public static final double TURN_MAX_MS = 2000.0;
    public static final double SPEAKER_THRESHOLD = 0.45;
    /**
     * Greet the owner by name when they sit down or come back after an absence (v1 contract C5: the key and its default
     * are frozen; the voice reads it from voice.json).
     */
    public static final boolean GREET_ON_SIT = true;
    /** Names and terms speech recognition must get right; the owner adds theirs (people, places, projects). */
    public static final List<String> VOCABULARY = List.of("Marvin");
    public static final int MAX_TERMS = 40;
    public static final int MAX_TERM = 40;

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
                case "vision_model" -> {
                    v = v == null ? "" : v;
                    if (!(v instanceof String s) || !s.isEmpty() && !MODEL.matcher(s).matches()) {
                        throw bad("vision_model must be an Ollama model name, or empty to use the voice's model");
                    }
                }
                case "camera" -> {
                    if (!(v instanceof String c) || !CAMERAS.contains(c)) {
                        throw bad("camera must be one of " + String.join(", ", CAMERAS));
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
                        throw bad("stt_model must be a Whisper model name (tiny, small, turbo...), a Parakeet repo, or auto");
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
                case "vocabulary" -> v = vocabulary(v);
                case "wake", "reminders", "welcome_back", "greet_on_sit", "tools", "internet", "known_voices_only" -> {
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

    /**
     * The vocabulary as the app sends it: a list of names and terms, or one string with commas or new lines between
     * them; each stripped, printable, at most {@link #MAX_TERM} characters, duplicates removed, at most
     * {@link #MAX_TERMS}.
     */
    static List<String> vocabulary(Object v) {
        List<?> items;
        if (v instanceof String s) {
            items = List.of(s.split("[,\\n]"));
        } else if (v instanceof List<?> l) {
            items = l;
        } else {
            throw bad("vocabulary must be a list of names and words, e.g. [\"Marvin\", \"Aashma\"]");
        }
        List<String> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object o : items) {
            if (!(o instanceof String s)) {
                throw bad("vocabulary must be a list of names and words");
            }
            String t = String.join(" ", SPACES.split(s.strip()));
            if (t.isEmpty()) {
                continue;
            }
            if (t.codePointCount(0, t.length()) > MAX_TERM || !printable(t) || t.chars().anyMatch(c -> "{}<>[]\\\"`".indexOf(c) >= 0)) {
                throw bad("each word of the vocabulary must be at most " + MAX_TERM + " printable characters");
            }
            if (seen.add(t.toLowerCase(java.util.Locale.ROOT))) {
                out.add(t);
            }
        }
        if (out.size() > MAX_TERMS) {
            throw bad("the vocabulary holds at most " + MAX_TERMS + " names and words");
        }
        return List.copyOf(out);
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
        out.put("follow_up_s", FOLLOW_UP_S);
        out.put("tools", true);
        out.put("internet", true);
        out.put("home_place", "");
        out.put("reminders", true);
        out.put("welcome_back", false);
        out.put("greet_on_sit", GREET_ON_SIT);
        out.put("vision_model", "");
        out.put("camera", "auto");
        out.put("vocabulary", VOCABULARY);
        out.put("known_voices_only", false);
        for (String k : APP_KEYS) {
            if (settings.containsKey(k)) {
                out.put(k, settings.get(k));
            }
        }
        return out;
    }

    /**
     * The configuration {@code settings} (voice.json's keys) give, every default filled in. The devices: the computer's
     * and the robot's profiles ({@link AudioDeviceProfile}), changed by voice.json's {@code devices}
     * ({@code {"computer": {...}, "robot": {...}}}); the older top-level {@code duplex}, {@code echo_tail_s},
     * {@code input_device} and {@code output_device} still set the computer's.
     */
    public static VoiceConfig config(Map<String, Object> settings) {
        String language = str(settings, "language", null);
        if ("auto".equals(language) || "".equals(language)) {
            language = null;
        }
        String defaultLanguage = language != null ? language : str(settings, "default_language", "fr");
        Map<String, Object> legacy = new LinkedHashMap<>();
        for (String k : List.of("duplex", "echo_tail_s", "input_device", "output_device")) {
            if (settings.get(k) != null) {
                legacy.put(k, settings.get(k));
            }
        }
        Map<?, ?> devices = settings.get("devices") instanceof Map<?, ?> d ? d : Map.of();
        AudioDeviceProfile computer = AudioDeviceProfile.with(AudioDeviceProfile.with(AudioDeviceProfile.COMPUTER_DEFAULT,
                legacy), devices.get("computer") instanceof Map<?, ?> m ? m : null);
        AudioDeviceProfile robot = AudioDeviceProfile.with(AudioDeviceProfile.ROBOT_DEFAULT,
                devices.get("robot") instanceof Map<?, ?> m ? m : null);
        double endSilenceMs = num(settings, "end_silence_ms", END_SILENCE_MS);
        return new VoiceConfig(
                str(settings, "llm_model", DEFAULT_MODEL), str(settings, "ollama_host", DEFAULT_OLLAMA),
                language, defaultLanguage,
                str(settings, "stt", "auto"), str(settings, "stt_model", null),
                str(settings, "tts", "auto"), str(settings, "tts_voice", null),
                bool(settings, "wake", true), num(settings, "follow_up_s", FOLLOW_UP_S),
                positive(settings, "listen_window_s", LISTEN_WINDOW_S), bool(settings, "speculative_stt", true),
                positive(settings, "speculate_after_s", SPECULATE_AFTER_S),
                endSilenceMs > 0 ? endSilenceMs : END_SILENCE_MS,
                nonNegative(settings, "end_silence_long_ms", END_SILENCE_LONG_MS),
                nonNegative(settings, "continue_grace_s", CONTINUE_GRACE_S),
                nonNegative(settings, "continue_join_s", CONTINUE_JOIN_S),
                bool(settings, "chime", true),
                bool(settings, "tools", true), bool(settings, "internet", true),
                str(settings, "home_place", "").strip(), bool(settings, "reminders", true),
                bool(settings, "welcome_back", false), route(str(settings, "audio_route", "computer")),
                computer, robot, str(settings, "vision_model", "").strip(), camera(str(settings, "camera", "auto")),
                choice(str(settings, "vad", "auto"), VAD_CHOICES), choice(str(settings, "turn", "auto"), TURN_CHOICES),
                positive(settings, "turn_check_ms", TURN_CHECK_MS), positive(settings, "turn_max_ms", TURN_MAX_MS),
                terms(settings.get("vocabulary")), bool(settings, "known_voices_only", false),
                positive(settings, "speaker_threshold", SPEAKER_THRESHOLD));
    }

    private static String choice(String s, List<String> choices) {
        return choices.contains(s) ? s : "auto";
    }

    /** voice.json's vocabulary, or the default when it is missing or not a list of words. */
    private static List<String> terms(Object v) {
        if (v == null) {
            return VOCABULARY;
        }
        try {
            return vocabulary(v);
        } catch (InvalidVoiceSettingException e) {
            return VOCABULARY;
        }
    }

    private static double positive(Map<String, Object> m, String k, double dflt) {
        double v = num(m, k, dflt);
        return v > 0 ? v : dflt;
    }

    private static double nonNegative(Map<String, Object> m, String k, double dflt) {
        return Math.max(0.0, num(m, k, dflt));
    }

    private static String camera(String s) {
        return CAMERAS.contains(s) ? s : "auto";
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
     * @param language         forced language, {@code null} to follow the speaker
     * @param endSilenceMs     the silence that ends a question
     * @param endSilenceLongMs the end-of-question silence when the words so far announce more; 0: never longer
     * @param continueGraceS   speech resuming this soon after a question, before its answer is heard, continues it; 0:
     *                         never
     * @param continueJoinS    a question interrupting the answer (barge-in, Talk now) this soon after the one it cut
     *                         joins it; 0: never
     * @param chime            the voice's earcons (listening opens and closes, errors)
     * @param audioRoute       {@code computer}, {@code robot} or {@code auto}
     * @param computer         the computer's audio device
     * @param robot            a robot's audio device (its name filled in when one is chosen)
     * @param visionModel      the model for questions with an image; empty: the voice's model ({@link #imageModel()})
     * @param camera           which camera the {@code look} tool uses: {@code auto}, {@code computer}, {@code robot}, {@code off}
     * @param vad              voice activity: {@code auto}, {@code silero}, {@code webrtc}, {@code energy}
     * @param turn             the end of a question: {@code auto}, {@code smart-turn}, {@code silence}
     * @param turnCheckMs      Smart Turn judges the turn after this much silence
     * @param turnMaxMs        a turn it finds unfinished ends after this much silence
     * @param vocabulary       names and terms speech recognition must get right
     * @param knownVoicesOnly  questions from voices Marvin does not know are ignored
     * @param speakerThreshold the similarity from which a voice is recognised
     */
    public record VoiceConfig(String llmModel, String ollamaHost, String language, String defaultLanguage,
                              String stt, String sttModel, String tts, String ttsVoice, boolean wake,
                              double followUpS, double listenWindowS, boolean speculativeStt, double speculateAfterS,
                              double endSilenceMs, double endSilenceLongMs, double continueGraceS, double continueJoinS,
                              boolean chime, boolean tools, boolean internet, String homePlace, boolean reminders,
                              boolean welcomeBack, String audioRoute, AudioDeviceProfile computer,
                              AudioDeviceProfile robot, String visionModel, String camera, String vad, String turn,
                              double turnCheckMs, double turnMaxMs, List<String> vocabulary, boolean knownVoicesOnly,
                              double speakerThreshold) {

        /** The model that answers a question with an image: the vision model, or the voice's when there is none. */
        public String imageModel() {
            return visionModel == null || visionModel.isBlank() ? llmModel : visionModel;
        }

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

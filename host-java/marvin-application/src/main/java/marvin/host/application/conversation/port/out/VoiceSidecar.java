// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import marvin.host.domain.conversation.AudioDeviceProfile;
import marvin.host.domain.conversation.SpeechLanguage;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.Voiceprint;

/**
 * The voice: the real-time audio loop in its own process (the voice sidecar, docs/design.md 4.3). It hears,
 * recognises and speaks; the conversation service decides what to say. One session at a time: while it is
 * open, the sidecar listens with the given settings and reports what it hears and does.
 */
public interface VoiceSidecar {

    /**
     * Opens the session (starting the sidecar if needed) with these settings, or applies new settings to the
     * open one. {@code signals} gets everything the voice reports, from the sidecar's thread.
     */
    void open(Settings settings, Signals signals);

    /** Closes the session: the voice stops listening and speaking. */
    void close();

    /** Sends a command to the open session; ignored when none is open. */
    void send(Command command);

    /** What the voice can offer on this computer (backends, voices), or empty when it cannot say. */
    Optional<Options> options();

    /**
     * Everything the voice is built from (contract version 2: every value is sent, defaults included, so the voice
     * has none of its own), and the audio device the route chose, with its profile.
     *
     * @param endSilenceLongMs 0: never longer than {@code endSilenceMs}
     * @param continueGraceS   0: a question that goes on is never joined to the one before
     * @param continueJoinS    0: a question that interrupts an answer never joins the question it cut
     * @param ears             the ears: the VAD, the end of a question, the vocabulary, the people it knows
     */
    record Settings(String stt, String sttModel, String tts, String ttsVoice, String language, String defaultLanguage,
                    boolean wake, double followUpS, double listenWindowS, boolean speculativeStt, double speculateAfterS,
                    double endSilenceMs, double endSilenceLongMs, double continueGraceS, double continueJoinS,
                    AudioDeviceProfile device, Ears ears) {

        public Settings {
            ears = ears == null ? Ears.DEFAULT : ears;
        }

        public Settings(String stt, String sttModel, String tts, String ttsVoice, String language, String defaultLanguage,
                        boolean wake, double followUpS, double listenWindowS, boolean speculativeStt,
                        double speculateAfterS, double endSilenceMs, double endSilenceLongMs, double continueGraceS,
                        double continueJoinS, AudioDeviceProfile device) {
            this(stt, sttModel, tts, ttsVoice, language, defaultLanguage, wake, followUpS, listenWindowS, speculativeStt,
                    speculateAfterS, endSilenceMs, endSilenceLongMs, continueGraceS, continueJoinS, device, Ears.DEFAULT);
        }

        /** The configuration's settings, on {@code device}. */
        public static Settings of(VoiceSettings.VoiceConfig c, AudioDeviceProfile device) {
            return of(c, device, List.of());
        }

        /** The configuration's settings, on {@code device}, knowing these people's voices. */
        public static Settings of(VoiceSettings.VoiceConfig c, AudioDeviceProfile device, List<Voiceprint> voiceprints) {
            return new Settings(c.stt(), c.sttModel(), c.tts(), c.ttsVoice(), c.language(), c.defaultLanguage(), c.wake(),
                    c.followUpS(), c.listenWindowS(), c.speculativeStt(), c.speculateAfterS(), c.endSilenceMs(),
                    c.endSilenceLongMs(), c.continueGraceS(), c.continueJoinS(), device,
                    new Ears(c.vad(), c.turn(), c.turnCheckMs(), c.turnMaxMs(), c.vocabulary(), voiceprints,
                            c.knownVoicesOnly(), c.speakerThreshold()));
        }

        /** On the robot's microphone and speaker. */
        public boolean robot() {
            return AudioDeviceProfile.ROBOT.equals(device.kind());
        }

        /** The same settings on another device. */
        public Settings on(AudioDeviceProfile other) {
            return new Settings(stt, sttModel, tts, ttsVoice, language, defaultLanguage, wake, followUpS, listenWindowS,
                    speculativeStt, speculateAfterS, endSilenceMs, endSilenceLongMs, continueGraceS, continueJoinS, other,
                    ears);
        }
    }

    /**
     * The ears' settings (docs/voice.md, "Ears"): voice activity, the end of a question, the vocabulary, and the
     * people whose voice Marvin knows.
     *
     * @param vad              {@code auto}, {@code silero}, {@code webrtc}, {@code energy}
     * @param turn             {@code auto}, {@code smart-turn}, {@code silence}
     * @param turnCheckMs      Smart Turn judges the turn after this much silence
     * @param turnMaxMs        a turn it finds unfinished ends after this much silence
     * @param vocabulary       names and terms speech recognition must get right
     * @param voiceprints      the people whose voice it knows
     * @param knownVoicesOnly  questions from voices it does not know are ignored
     * @param speakerThreshold the similarity from which a voice is recognised
     */
    record Ears(String vad, String turn, double turnCheckMs, double turnMaxMs, List<String> vocabulary,
                List<Voiceprint> voiceprints, boolean knownVoicesOnly, double speakerThreshold) {

        public static final Ears DEFAULT = new Ears("auto", "auto", VoiceSettings.TURN_CHECK_MS, VoiceSettings.TURN_MAX_MS,
                VoiceSettings.VOCABULARY, List.of(), false, VoiceSettings.SPEAKER_THRESHOLD);

        public Ears {
            vocabulary = vocabulary == null ? List.of() : List.copyOf(vocabulary);
            voiceprints = voiceprints == null ? List.of() : List.copyOf(voiceprints);
        }
    }

    /** Receives the voice's signals. */
    interface Signals {
        void signal(Signal signal);
    }

    /** What the voice reports. */
    sealed interface Signal {
    }

    /**
     * The voice's state: {@code starting}, {@code idle}, {@code listening}, {@code thinking},
     * {@code speaking}, {@code stopped} or {@code error} (with {@code error} and {@code fix}).
     *
     * @param listenS seconds left to talk without the name, in a listening window; else {@code null}
     * @param hearing someone talks in the listening window (or their words are being understood): it waits for them,
     *                {@code listenS} is {@code null} meanwhile
     * @param asked   listening because it was asked to (Talk now, "Marvin." alone), not the follow-up window
     * @param device  the device it hears and speaks with: {@code computer}, a robot's name, or empty when not running
     */
    record Status(String state, boolean muted, String error, String fix, String stt, String tts, Double listenS,
                  boolean hearing, boolean asked, String device) implements Signal {

        public Status(String state, boolean muted, String error, String fix, String stt, String tts, Double listenS,
                      boolean hearing) {
            this(state, muted, error, fix, stt, tts, listenS, hearing, false, "");
        }

        public Status(String state, boolean muted, String error, String fix, String stt, String tts, Double listenS) {
            this(state, muted, error, fix, stt, tts, listenS, false);
        }
    }

    /**
     * A question for Marvin: {@code uid} identifies it in the session; {@code latency} holds the listening stages.
     * {@code continues}: the earlier questions (their uids) this one goes on from; their text starts {@code text}, and
     * they were cancelled (before their answer was heard, or cut by this one).
     */
    record Heard(long uid, String text, String raw, String language, String source, Map<String, Double> latency,
                 double wallTime, List<Long> continues, String speaker, Double speakerScore) implements Signal {

        public Heard {
            continues = continues == null ? List.of() : List.copyOf(continues);
            speaker = speaker == null ? "" : speaker;
        }

        public Heard(long uid, String text, String raw, String language, String source, Map<String, Double> latency,
                     double wallTime, List<Long> continues) {
            this(uid, text, raw, language, source, latency, wallTime, continues, "", null);
        }

        public Heard(long uid, String text, String raw, String language, String source, Map<String, Double> latency,
                     double wallTime) {
            this(uid, text, raw, language, source, latency, wallTime, List.of());
        }

        /** A person Marvin knows said it (not {@code unknown}, not untold). */
        public boolean knownSpeaker() {
            return !speaker.isEmpty() && !"unknown".equals(speaker);
        }
    }

    /**
     * How a calibration goes ({@link Enroll}): {@code noise} (stay quiet {@code seconds}), {@code sentence} (read
     * sentence {@code index}: {@code text}), {@code heard} (what was heard of it: {@code text}, {@code match},
     * {@code dbfs}), {@code retry} ({@code message}), {@code done} ({@code result}), {@code failed} ({@code message}),
     * {@code cancelled}.
     */
    record EnrollProgress(String state, int index, String text, double match, double dbfs, String message, double seconds,
                          EnrollResult result) implements Signal {
    }

    /**
     * What a calibration measured on a device ({@code kind}: computer or robot) and, with a speaker model, the person's
     * voiceprint ({@code null} without).
     */
    record EnrollResult(String name, String kind, String robot, double noiseDbfs, double speechDbfs, double vadMinDbfs,
                        double speechMinDbfs, double vadThreshold, Voiceprint voiceprint, List<String> warnings,
                        List<String> transcripts) {

        public EnrollResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            transcripts = transcripts == null ? List.of() : List.copyOf(transcripts);
        }
    }

    /** Heard, not answered, and why. */
    record Ignored(String text, String reason, Double dbfs) implements Signal {
    }

    /**
     * Microphone loudness (0..1), and in dBFS ({@code null} from a sidecar that does not say it; -100: muted).
     */
    record Level(double mic, boolean speech, boolean gated, Double dbfs) implements Signal {
        public Level(double mic, boolean speech, boolean gated) {
            this(mic, speech, gated, null);
        }
    }

    /**
     * The ears' diagnostics (the app's Senses screen): an end-of-turn decision ({@code turn}), or what became of an
     * utterance ({@code utterance}). Values the sidecar did not send are {@code null}; strings are never null.
     *
     * @param outcome heard, listening (the name alone), ignored, dropped (not judged)
     */
    record Hearing(String kind, long uid, double wallTime, String vad, String turn, Boolean complete, Double probability,
                   Double turnS, double durationS, Double dbfs, String stt, Double sttS, boolean speculative, String text,
                   String language, Double languageProb, boolean wake, String speaker, Double speakerScore,
                   String outcome, String reason) implements Signal {

        /** As the app reads it (snake_case keys; what was not sent is left out). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("kind", kind);
            m.put("uid", uid);
            m.put("t", wallTime);
            m.put("vad", vad);
            m.put("turn", turn);
            if ("turn".equals(kind)) {
                m.put("complete", complete);
                m.put("probability", probability);
                m.put("turn_s", turnS);
                return m;
            }
            m.put("duration_s", durationS);
            m.put("dbfs", dbfs);
            m.put("stt", stt);
            m.put("stt_s", sttS);
            m.put("speculative", speculative);
            m.put("text", text);
            m.put("language", language);
            m.put("language_prob", languageProb);
            m.put("wake", wake);
            m.put("speaker", speaker);
            m.put("speaker_score", speakerScore);
            m.put("outcome", outcome);
            m.put("reason", reason);
            return m;
        }
    }

    /** The words understood so far of the utterance {@code uid}. */
    record Partial(long uid, String text) implements Signal {
    }

    /** Someone started ({@code start}), stopped ({@code end}) talking, or the utterance was judged ({@code done}). */
    record Utterance(String state, long uid) implements Signal {
    }

    /** A piece of speech queued on the speaker, with its loudness at 20 values per second. */
    record SayProgress(long replyId, String text, double seconds, List<Double> envelope) implements Signal {
    }

    /**
     * Speech stopped or was skipped: {@code barge-in}, {@code stop}, {@code busy}, {@code off}, or {@code merged} (the
     * question goes on: a {@link Heard} that continues it follows).
     */
    record Interrupted(long replyId, String reason, long utteranceUid) implements Signal {
    }

    /** An answer (or proactive speech) has been said: what, and the speaking stages' latencies. */
    record ReplySpoken(long replyId, long utteranceUid, String text, boolean interrupted, Map<String, Double> latency)
            implements Signal {
    }

    /** Commands for the voice. */
    sealed interface Command {
    }

    /** An answer begins ({@code utteranceUid} 0: Marvin speaks on its own); text pieces follow. */
    record ReplyStart(long replyId, String language, boolean proactive, long utteranceUid) implements Command {
    }

    /** One clause of the answer, cleaned for speech, and the language to read it in. */
    record Text(long replyId, String text, String language) implements Command {
    }

    /** The answer is complete; {@code error} ({@code llm_down}, {@code error} or empty) if it failed. */
    record ReplyEnd(long replyId, String error) implements Command {
    }

    /** Speak a whole text now, cut into clauses tagged with their language. */
    record Say(long replyId, String text, String language, boolean force, boolean proactive, List<SpeechLanguage.Clause> clauses)
            implements Command {

        public Say {
            clauses = clauses == null ? List.of() : List.copyOf(clauses);
        }
    }

    /** A short sound on the voice's device: {@code wake}, {@code done} or {@code error}. */
    record Earcon(String sound) implements Command {
    }

    /** A few words while a tool runs. */
    record Filler(long replyId, String text, String language) implements Command {
    }

    /** Talk now ({@code on}), or stop listening. */
    record ListenNow(boolean on) implements Command {
    }

    record Mute(boolean muted) implements Command {
    }

    record StopSpeaking() implements Command {
    }

    /** A typed question, answered like a heard one. */
    record Ask(String text, String language) implements Command {
    }

    /**
     * Calibrate the voice's device and learn {@code name}'s voice from the {@code sentences} read aloud
     * ({@code action} {@code start}), or stop ({@code cancel}).
     */
    record Enroll(String action, String name, List<String> sentences, double noiseS) implements Command {

        public Enroll {
            sentences = sentences == null ? List.of() : List.copyOf(sentences);
        }
    }

    /** The voice's options on this computer; {@code ears}: the ears' models, and whether each is here. */
    record Options(List<Backend> stt, List<String> sttModels, List<Backend> tts, List<Voice> voices, List<Backend> ears) {

        public Options {
            ears = ears == null ? List.of() : List.copyOf(ears);
        }

        public Options(List<Backend> stt, List<String> sttModels, List<Backend> tts, List<Voice> voices) {
            this(stt, sttModels, tts, voices, List.of());
        }
    }

    record Backend(String name, boolean installed, String why) {
    }

    /** {@code engine}: qwen, piper, say or espeak; {@code locale}: e.g. fr_FR. */
    record Voice(String id, String engine, String language, String locale, boolean installed) {
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import marvin.host.domain.conversation.AudioDeviceProfile;
import marvin.host.domain.conversation.SpeechLanguage;
import marvin.host.domain.conversation.VoiceSettings;

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
     */
    record Settings(String stt, String sttModel, String tts, String ttsVoice, String language, String defaultLanguage,
                    boolean wake, double followUpS, double listenWindowS, boolean speculativeStt, double speculateAfterS,
                    double endSilenceMs, double endSilenceLongMs, double continueGraceS, double continueJoinS,
                    AudioDeviceProfile device) {

        /** The configuration's settings, on {@code device}. */
        public static Settings of(VoiceSettings.VoiceConfig c, AudioDeviceProfile device) {
            return new Settings(c.stt(), c.sttModel(), c.tts(), c.ttsVoice(), c.language(), c.defaultLanguage(), c.wake(),
                    c.followUpS(), c.listenWindowS(), c.speculativeStt(), c.speculateAfterS(), c.endSilenceMs(),
                    c.endSilenceLongMs(), c.continueGraceS(), c.continueJoinS(), device);
        }

        /** On the robot's microphone and speaker. */
        public boolean robot() {
            return AudioDeviceProfile.ROBOT.equals(device.kind());
        }

        /** The same settings on another device. */
        public Settings on(AudioDeviceProfile other) {
            return new Settings(stt, sttModel, tts, ttsVoice, language, defaultLanguage, wake, followUpS, listenWindowS,
                    speculativeStt, speculateAfterS, endSilenceMs, endSilenceLongMs, continueGraceS, continueJoinS, other);
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
                 double wallTime, List<Long> continues) implements Signal {

        public Heard {
            continues = continues == null ? List.of() : List.copyOf(continues);
        }

        public Heard(long uid, String text, String raw, String language, String source, Map<String, Double> latency,
                     double wallTime) {
            this(uid, text, raw, language, source, latency, wallTime, List.of());
        }
    }

    /** Heard, not answered, and why. */
    record Ignored(String text, String reason, Double dbfs) implements Signal {
    }

    /** Microphone loudness (0..1). */
    record Level(double mic, boolean speech, boolean gated) implements Signal {
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

    /** The voice's options on this computer. */
    record Options(List<Backend> stt, List<String> sttModels, List<Backend> tts, List<Voice> voices) {
    }

    record Backend(String name, boolean installed, String why) {
    }

    /** {@code engine}: qwen, piper, say or espeak; {@code locale}: e.g. fr_FR. */
    record Voice(String id, String engine, String language, String locale, boolean installed) {
    }
}

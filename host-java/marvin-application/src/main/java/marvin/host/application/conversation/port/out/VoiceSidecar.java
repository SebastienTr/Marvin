// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** The voice's settings (the {@code voice.json} keys the audio uses). {@code robot}: hear and speak through the robot. */
    record Settings(String stt, String sttModel, String tts, String ttsVoice, String language, String defaultLanguage,
                    boolean wake, boolean duplex, double echoTailS, double followUpS, double listenWindowS,
                    boolean speculativeStt, double endSilenceMs, boolean chime, String inputDevice,
                    String outputDevice, boolean robot) {
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
     */
    record Status(String state, boolean muted, String error, String fix, String stt, String tts, Double listenS)
            implements Signal {
    }

    /** A question for Marvin: {@code uid} identifies it in the session; {@code latency} holds the listening stages. */
    record Heard(long uid, String text, String raw, String language, String source, Map<String, Double> latency,
                 double wallTime) implements Signal {
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

    /** Speech stopped or was skipped: {@code barge-in}, {@code stop}, {@code busy} or {@code off}. */
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

    /** A piece of the answer, reasoning and tool payloads removed. */
    record Text(long replyId, String text) implements Command {
    }

    /** The answer is complete; {@code error} ({@code llm_down}, {@code error} or empty) if it failed. */
    record ReplyEnd(long replyId, String error) implements Command {
    }

    /** Speak a whole text now. */
    record Say(long replyId, String text, String language, boolean force, boolean proactive) implements Command {
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

    /** {@code engine}: piper, say or espeak; {@code locale}: e.g. fr_FR. */
    record Voice(String id, String engine, String language, String locale, boolean installed) {
    }
}

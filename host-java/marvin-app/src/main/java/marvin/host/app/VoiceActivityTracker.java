// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import marvin.host.application.conversation.port.out.VoiceListener;
import marvin.host.application.memory.port.out.VoiceActivity;
import marvin.host.domain.conversation.VoiceSnapshot;
import marvin.host.domain.shared.Clocks;

/**
 * Whether someone is talking with Marvin, for the memory worker: the voice's live signals (speech heard, words said,
 * a question transcribed) and its state (listening, thinking, speaking).
 */
final class VoiceActivityTracker implements VoiceListener, VoiceActivity {
    static final Set<String> ACTIVE_STATUS = Set.of("listening", "thinking", "speaking");
    static final Set<String> SIGNS = Set.of("utterance", "partial", "say", "transcript");
    /** A sign of conversation this recent counts as busy even between two statuses. */
    static final double RECENT_S = 15;

    private final Clocks clocks;
    private final Supplier<VoiceSnapshot> voice;
    /** The last sign of conversation (0: none since start). */
    private volatile double lastSign;
    /** Idle time counts from here: the last sign, or the host's start. */
    private volatile double last;

    VoiceActivityTracker(Clocks clocks, Supplier<VoiceSnapshot> voice) {
        this.clocks = clocks;
        this.voice = voice;
        this.last = clocks.wallSeconds();           // the host just started: idle counts from now
    }

    @Override
    public void onVoice(String kind, Map<String, Object> payload) {
        if (SIGNS.contains(kind)) {
            lastSign = clocks.wallSeconds();
            last = lastSign;
        }
    }

    @Override
    public boolean busy() {
        if (lastSign > 0 && clocks.wallSeconds() - lastSign < RECENT_S) {
            return true;
        }
        VoiceSnapshot s = voice.get();
        return s != null && ("starting".equals(s.state()) || ACTIVE_STATUS.contains(s.status()));
    }

    @Override
    public double lastActivity() {
        return last;
    }
}

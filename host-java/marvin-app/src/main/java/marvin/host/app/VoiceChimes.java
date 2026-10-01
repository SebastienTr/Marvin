// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import marvin.host.application.conversation.port.out.VoiceListener;
import marvin.host.application.robot.port.in.RobotChimes;

/**
 * The voice's earcons, decided in one place and played on one device (docs/voice.md, "Earcons"). From the voice's
 * state as the app sees it: {@code wake} when it starts listening because it was asked to (Talk now, "Marvin." alone,
 * from any state: not the follow-up window after an answer), {@code done} when such a window ends (a question was
 * heard, or nothing came), {@code wake} / {@code done} when it is unmuted / muted, {@code error} when it fails. One sound
 * per change at most, none when the voice's {@code chime} setting is off.
 *
 * <p>Where it plays ({@code earcons} in the snapshot): on the voice's own device when it runs and has some (the
 * computer's speaker through the voice sidecar, a robot's built-in sounds), else on the bench boards' buzzers. Never
 * both, and the app's own sounds stand in only when neither plays. The conversation and robot contexts only meet here,
 * through their ports.
 */
public final class VoiceChimes implements VoiceListener {
    private final RobotChimes buzzers;
    private final Predicate<String> voiceDevice;
    private Map<String, Object> last;

    /**
     * @param buzzers     the bench boards' buzzers
     * @param voiceDevice plays a sound on the voice's device; false when it could not
     */
    public VoiceChimes(RobotChimes buzzers, Predicate<String> voiceDevice) {
        this.buzzers = Objects.requireNonNull(buzzers, "buzzers");
        this.voiceDevice = Objects.requireNonNull(voiceDevice, "voiceDevice");
    }

    @Override
    public void onVoice(String kind, Map<String, Object> payload) {
        if (!"voice".equals(kind)) {
            return;
        }
        Map<String, Object> before;
        synchronized (this) {
            before = last;
            last = new HashMap<>(payload);
        }
        String sound = chime(before, payload);
        if (sound == null) {
            return;
        }
        if (!"device".equals(payload.get("earcons")) || !voiceDevice.test(sound)) {
            buzzers.chime(sound);
        }
    }

    /** The sound for a change of the voice's snapshot ({@code before} is {@code null} at first), or {@code null}. */
    static String chime(Map<String, Object> before, Map<String, Object> after) {
        if (Boolean.FALSE.equals(after.get("chime")) || "none".equals(after.get("earcons"))) {
            return null;
        }
        Map<String, Object> b = before != null ? before : Map.of();
        if ("error".equals(after.get("state")) && !"error".equals(b.get("state"))) {
            return "error";
        }
        boolean wasAsked = "listening".equals(b.get("status")) && Boolean.TRUE.equals(b.get("asked"));
        boolean asked = "listening".equals(after.get("status")) && Boolean.TRUE.equals(after.get("asked"));
        if (asked && !wasAsked) {
            return "wake";
        }
        if (wasAsked && !"listening".equals(after.get("status"))) {
            return "done";
        }
        if (before != null && Boolean.TRUE.equals(b.get("muted")) != Boolean.TRUE.equals(after.get("muted"))) {
            return Boolean.TRUE.equals(after.get("muted")) ? "done" : "wake";
        }
        return null;
    }
}

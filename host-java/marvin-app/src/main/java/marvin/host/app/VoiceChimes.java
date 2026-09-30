// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import marvin.host.application.conversation.port.out.VoiceListener;
import marvin.host.application.robot.port.in.RobotChimes;

/**
 * The voice's moments as chimes on the robots with a buzzer (the bench board), from the voice's state as the app
 * sees it: {@code error} when it fails, {@code wake} when it starts listening from idle (Talk now, or "Marvin." alone;
 * not the follow-up window after an answer), {@code done} when a listening window ends; {@code wake} / {@code done}
 * when it is unmuted / muted. One sound per change at most, none when the voice's chime setting is off. The same
 * rules as {@code host/marvin_host/buttons.py}. The conversation and robot contexts only meet here, through their ports.
 */
public final class VoiceChimes implements VoiceListener {
    private final RobotChimes chimes;
    private Map<String, Object> last;

    public VoiceChimes(RobotChimes chimes) {
        this.chimes = Objects.requireNonNull(chimes, "chimes");
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
        if (sound != null) {
            chimes.chime(sound);
        }
    }

    /** The sound for a change of the voice's snapshot ({@code before} is {@code null} at first), or {@code null}. */
    static String chime(Map<String, Object> before, Map<String, Object> after) {
        if (Boolean.FALSE.equals(after.get("chime"))) {
            return null;
        }
        Map<String, Object> b = before != null ? before : Map.of();
        if ("error".equals(after.get("state")) && !"error".equals(b.get("state"))) {
            return "error";
        }
        Object was = b.getOrDefault("status", "off");
        Object now = after.getOrDefault("status", "off");
        if ("listening".equals(now) && "idle".equals(was)) {
            return "wake";
        }
        if ("listening".equals(was) && ("thinking".equals(now) || "idle".equals(now))) {
            return "done";
        }
        if (before != null && Boolean.TRUE.equals(b.get("muted")) != Boolean.TRUE.equals(after.get("muted"))) {
            return Boolean.TRUE.equals(after.get("muted")) ? "done" : "wake";
        }
        return null;
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.Map;

/**
 * {@code BUTTON} payload: button (u8) and action (u8). A longer payload keeps this prefix; the host ignores
 * buttons and actions it does not know.
 */
public record Button(int button, int action) {
    /** The app's Talk now. */
    public static final int TALK = 1;
    /** Mutes or unmutes the microphone. */
    public static final int MUTE = 2;
    public static final int PRESS = 1;
    public static final int SIZE = 2;

    private static final Map<Integer, String> NAMES = Map.of(TALK, "talk", MUTE, "mute");

    public byte[] encode() {
        return new byte[] {(byte) button, (byte) action};
    }

    public static Button decode(byte[] payload) {
        if (payload.length < SIZE) {
            throw new ProtocolException("BUTTON too short");
        }
        return new Button(payload[0] & 0xff, payload[1] & 0xff);
    }

    /** {@code talk}, {@code mute}, or {@code null} for a button this host does not know. */
    public static String name(int button) {
        return NAMES.get(button);
    }

    /** The button ids this host knows, by name. */
    public static Map<Integer, String> names() {
        return NAMES;
    }
}

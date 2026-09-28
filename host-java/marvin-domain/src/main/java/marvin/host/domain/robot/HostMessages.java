// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.Map;

/** Payloads of the small host → robot messages: {@code HOST_ACK}, {@code FACE_EVENT}, {@code AUDIO_CTRL}, {@code SOUND}. */
public final class HostMessages {
    /** {@code AUDIO_CTRL} commands. */
    public static final int AUDIO_MIC_START = 1;
    public static final int AUDIO_MIC_STOP = 2;
    public static final int AUDIO_PLAY_STOP = 3;
    public static final int AUDIO_VOLUME = 4;
    public static final int AUDIO_MIC_GAIN = 5;

    /** Built-in sounds: name → id. Durations in {@link #SOUND_DURATIONS_MS}. */
    public static final Map<String, Integer> SOUNDS = Map.of(
            "chirp", 1, "beep", 2, "wake", 3, "done", 4, "error", 5, "hello", 6);
    public static final Map<String, Integer> SOUND_DURATIONS_MS = Map.of(
            "chirp", 140, "beep", 120, "wake", 200, "done", 200, "error", 360, "hello", 430);

    private HostMessages() {
    }

    /**
     * A whole {@code HOST_ACK} datagram. The header's sequence number and clock are 0, as the Python
     * host sends them; the host clock is only in the payload.
     */
    public static byte[] hostAck(long hostClockUs) {
        return Wire.pack(MessageType.HOST_ACK, 0, 0, Wire.le(8).putLong(hostClockUs).array());
    }

    /** The host clock carried by a {@code HOST_ACK} payload. */
    public static long hostAckClock(byte[] payload) {
        if (payload.length < 8) {
            throw new ProtocolException("HOST_ACK too short");
        }
        return Wire.le(payload).getLong();
    }

    /** {@code FACE_EVENT} payload: one event code. */
    public static byte[] faceEvent(int code) {
        return new byte[] {(byte) code};
    }

    /** {@code AUDIO_CTRL} payload; the argument is clamped to 0..255. */
    public static byte[] audioCtrl(int command, int argument) {
        return new byte[] {(byte) command, (byte) Math.max(0, Math.min(255, argument))};
    }

    /** {@code SOUND} payload for a name in {@link #SOUNDS}. */
    public static byte[] sound(String name) {
        Integer id = SOUNDS.get(name);
        if (id == null) {
            throw new IllegalArgumentException("unknown sound " + name);
        }
        return sound(id);
    }

    public static byte[] sound(int id) {
        return new byte[] {(byte) id};
    }
}

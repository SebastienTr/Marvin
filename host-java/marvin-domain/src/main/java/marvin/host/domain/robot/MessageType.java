// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.Optional;

/**
 * The message types of protocol v1 (docs/protocol.md). The code is the header's type byte.
 */
public enum MessageType {
    HELLO(0x01, Direction.ROBOT_TO_HOST),
    LIDAR(0x02, Direction.ROBOT_TO_HOST),
    LD2450(0x03, Direction.ROBOT_TO_HOST),
    LOG(0x04, Direction.ROBOT_TO_HOST),
    VITALS(0x05, Direction.ROBOT_TO_HOST),
    AUDIO_IN(0x06, Direction.ROBOT_TO_HOST),
    HOST_ACK(0x81, Direction.HOST_TO_ROBOT),
    FACE_STATE(0x82, Direction.HOST_TO_ROBOT),
    FACE_EVENT(0x83, Direction.HOST_TO_ROBOT),
    AUDIO_OUT(0x84, Direction.HOST_TO_ROBOT),
    AUDIO_CTRL(0x85, Direction.HOST_TO_ROBOT),
    SOUND(0x86, Direction.HOST_TO_ROBOT);

    /** Who sends a message type. */
    public enum Direction { ROBOT_TO_HOST, HOST_TO_ROBOT }

    private final int code;
    private final Direction direction;

    MessageType(int code, Direction direction) {
        this.code = code;
        this.direction = direction;
    }

    public int code() {
        return code;
    }

    public Direction direction() {
        return direction;
    }

    /** The type with this header code, or empty for a code this version does not know. */
    public static Optional<MessageType> fromCode(int code) {
        for (MessageType t : values()) {
            if (t.code == code) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}

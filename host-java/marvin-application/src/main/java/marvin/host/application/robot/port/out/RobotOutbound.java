// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.out;

import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.MessageType;

/**
 * Sends host → robot messages ({@code FACE_STATE}, {@code FACE_EVENT}, {@code AUDIO_OUT},
 * {@code AUDIO_CTRL}, {@code SOUND}) to where the device's {@code HELLO} came from. Never blocks for
 * long, never throws: a message that cannot go out is dropped, as UDP would.
 */
public interface RobotOutbound {

    void send(Device device, MessageType type, byte[] payload);
}

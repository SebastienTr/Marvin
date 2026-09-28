// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.in;

import marvin.host.domain.robot.SensorFrame;

/** A robot's microphone ({@code AUDIO_IN}), as it arrives: called from the socket thread, so it never blocks. */
public interface RobotAudioIn {

    void heard(SensorFrame.AudioChunk chunk);
}

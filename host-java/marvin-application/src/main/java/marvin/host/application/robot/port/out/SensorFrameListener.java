// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.out;

import marvin.host.domain.robot.SensorFrame;

/** Consumers of the robot's frames (the presence feed, the app's sensor views), called in order. Keep it short. */
public interface SensorFrameListener {

    void onFrame(SensorFrame frame);
}

// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.in;

import marvin.host.domain.robot.SensorFrame;

/** The robot link hands every checked frame here, one thread, in the order the datagrams arrived. */
public interface RobotInbound {

    void accept(SensorFrame frame);
}

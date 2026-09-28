// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.in;

import java.util.List;

import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.robot.SensorScene;

/** The devices the host has heard from. */
public interface RobotLinkQuery {

    /** Every device seen since start, for the app, as of the last {@link MonitorRobotLink#tick}. */
    List<DeviceStatus> devices();

    /** The devices that said {@code HELLO} (entities: their latest {@code HELLO}, their counters). */
    List<Device> connected();

    /** The sensor mini-views; {@code history}: with the vital sign rates of the last minutes. */
    SensorScene.View scene(boolean history);

    /** Some device is online. */
    boolean linked();
}

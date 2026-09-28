// SPDX-License-Identifier: MIT
package marvin.host.app.archfixture.domain.presence;

import marvin.host.app.archfixture.application.robot.port.in.RobotLinkQuery;
import marvin.host.app.archfixture.domain.robot.Device;
import marvin.host.app.archfixture.domain.robot.event.DeviceConnected;

/** Fixture: the presence context using the robot context the right way (port, event) and the wrong way (entity). */
public class Brainy {
    public String allowed(RobotLinkQuery link, DeviceConnected event) {
        return link.linked() ? event.name() : "";
    }

    public String forbidden(Device device) {
        return device.name();
    }
}

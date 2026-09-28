// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.in;

/** Called about once a second: rates, devices going offline and coming back, device logs. */
public interface MonitorRobotLink {

    void tick();
}

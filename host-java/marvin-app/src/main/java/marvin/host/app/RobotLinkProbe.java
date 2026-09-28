// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.List;
import java.util.stream.Collectors;

import marvin.host.adapter.robot.RobotLinkProperties;
import marvin.host.adapter.robot.UdpRobotLink;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.system.ComponentHealth;

/** The robot link in {@code /api/health}: listening or not, and which devices are online. */
public final class RobotLinkProbe implements ComponentProbe {
    private final UdpRobotLink link;
    private final RobotLinkQuery query;
    private final RobotLinkProperties props;

    public RobotLinkProbe(UdpRobotLink link, RobotLinkQuery query, RobotLinkProperties props) {
        this.link = link;
        this.query = query;
        this.props = props;
    }

    @Override
    public String name() {
        return "robot";
    }

    @Override
    public ComponentHealth check() {
        if (!props.enabled()) {
            return ComponentHealth.disabled("robot link off");
        }
        List<DeviceStatus> online = query.devices().stream().filter(DeviceStatus::online).toList();
        String devices = online.isEmpty() ? "no robot yet"
                : online.stream().map(d -> d.name() + " (" + d.role().wireName() + (d.simulated() ? ", simulated" : "")
                        + ")").collect(Collectors.joining(", "));
        if (!props.replay().isEmpty()) {
            return ComponentHealth.up("replaying " + props.replay() + ": " + devices);
        }
        if (!link.listening()) {
            return ComponentHealth.down("not listening on UDP " + props.port());
        }
        return ComponentHealth.up("UDP " + link.boundPort() + ": " + devices);
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import marvin.host.adapter.robot.UdpRobotLink;
import marvin.host.adapter.sidecar.PythonRuntime;
import marvin.host.adapter.sidecar.SidecarProperties;
import marvin.host.adapter.sidecar.SimulatorSidecar;

/** Demo mode: the Python simulator as the robot, sending to the host's UDP port once it is bound. */
public final class DemoRobot implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DemoRobot.class);
    private final SidecarProperties props;
    private final UdpRobotLink link;
    private SimulatorSidecar sim;
    private boolean started;

    public DemoRobot(SidecarProperties props, UdpRobotLink link) {
        this.props = props;
        this.link = link;
    }

    @Override
    public synchronized void start() {
        started = true;
        if (!link.listening()) {
            return;
        }
        PythonRuntime python = PythonRuntime.find(props).orElse(null);
        if (python == null) {
            log.warn("demo: no simulated robot (the Python host cannot run here)");
            return;
        }
        sim = new SimulatorSidecar(python, link.boundPort());
        sim.start();
    }

    @Override
    public synchronized void stop() {
        started = false;
        if (sim != null) {
            sim.stop();
            sim = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return started;
    }

    /** After the robot link. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE / 2 + 100;
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.application.robot;

import java.util.List;
import java.util.Objects;

import marvin.host.application.robot.port.in.MonitorRobotLink;
import marvin.host.application.robot.port.in.RobotInbound;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.robot.port.out.HostClock;
import marvin.host.application.robot.port.out.LinkNoticeListener;
import marvin.host.application.robot.port.out.SensorFrameListener;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.DeviceMonitor;
import marvin.host.domain.robot.SensorScene;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.SensorFrame;

/**
 * The robot link's use cases: every frame from the link goes to the device monitor and to the
 * listeners (the presence feed first); a tick turns counters into rates and notices.
 */
public final class RobotLinkService implements RobotInbound, RobotLinkQuery, MonitorRobotLink {
    private final DeviceMonitor monitor;
    private final SensorScene scene;
    private final List<SensorFrameListener> listeners;
    private final List<LinkNoticeListener> noticeListeners;
    private final HostClock clock;

    public RobotLinkService(Extrinsics extrinsics, HostClock clock, List<SensorFrameListener> listeners,
                            List<LinkNoticeListener> noticeListeners) {
        this.monitor = new DeviceMonitor(extrinsics.lidar());
        this.scene = new SensorScene(extrinsics);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listeners = List.copyOf(listeners);
        this.noticeListeners = List.copyOf(noticeListeners);
    }

    @Override
    public void accept(SensorFrame frame) {
        double now = clock.monotonicSeconds();
        monitor.record(frame, now, clock.wallSeconds());
        scene.record(frame, now);
        RuntimeException failure = null;
        for (SensorFrameListener l : listeners) {
            try {
                l.onFrame(frame);
            } catch (RuntimeException e) {
                failure = failure == null ? e : failure;     // the others still get the frame
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void tick() {
        for (DeviceMonitor.Notice n : monitor.tick(clock.monotonicSeconds())) {
            for (LinkNoticeListener l : noticeListeners) {
                l.onNotice(n);
            }
        }
    }

    @Override
    public List<DeviceStatus> devices() {
        return monitor.statuses();
    }

    @Override
    public List<Device> connected() {
        return monitor.devices();
    }

    @Override
    public SensorScene.View scene(boolean history) {
        return scene.view(clock.monotonicSeconds(), history);
    }

    @Override
    public boolean linked() {
        return monitor.anyOnline();
    }
}

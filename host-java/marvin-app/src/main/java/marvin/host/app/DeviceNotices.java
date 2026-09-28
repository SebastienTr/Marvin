// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Map;
import java.util.function.Supplier;

import marvin.host.adapter.web.EventHub;
import marvin.host.application.robot.port.out.LinkNoticeListener;
import marvin.host.application.system.port.in.HostLog;
import marvin.host.domain.robot.DeviceMonitor;
import marvin.host.domain.robot.DeviceStatus;

/**
 * The devices' news in the app (the Python {@code UIServer._on_sink}): their log lines, connections and
 * losses in the Log panel, and the devices list pushed to the app when it changes.
 */
public final class DeviceNotices implements LinkNoticeListener {
    private final HostLog log;
    private final EventHub hub;
    private final Supplier<Object> devices;

    public DeviceNotices(HostLog log, EventHub hub, Supplier<Object> devices) {
        this.log = log;
        this.hub = hub;
        this.devices = devices;
    }

    @Override
    public void onNotice(DeviceMonitor.Notice notice) {
        switch (notice) {
            case DeviceMonitor.Notice.Log l -> log.add("device", "info", l.text(), Map.of("device", l.device()));
            case DeviceMonitor.Notice.Connected c -> connected(c.device(), "connected");
            case DeviceMonitor.Notice.Reconnected r -> connected(r.device(), "reconnected");
            case DeviceMonitor.Notice.Disconnected d -> {
                log.add("device", "warning", d.device().name() + " stopped sending (link lost)",
                        Map.of("device", d.device().name()));
                hub.publish("devices", devices.get());
            }
        }
    }

    private void connected(DeviceStatus d, String kind) {
        String what = d.board() + ", firmware " + d.firmware() + (d.simulated() ? ", simulated sensors" : "");
        log.add("device", "info", d.name() + " " + kind + " (" + what + ")", Map.of("device", d.name()));
        hub.publish("devices", devices.get());
    }
}

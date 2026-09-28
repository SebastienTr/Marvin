// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.out;

import marvin.host.domain.robot.DeviceMonitor;

/** Hears about devices connecting, going offline, coming back, and their log lines. */
public interface LinkNoticeListener {

    void onNotice(DeviceMonitor.Notice notice);
}

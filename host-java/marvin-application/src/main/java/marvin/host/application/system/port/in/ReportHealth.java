// SPDX-License-Identifier: MIT
package marvin.host.application.system.port.in;

import marvin.host.domain.system.HostStatus;

/** Use case: how is the host doing right now. */
public interface ReportHealth {
    HostStatus health();
}

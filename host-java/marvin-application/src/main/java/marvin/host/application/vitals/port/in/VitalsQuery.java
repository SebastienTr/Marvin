// SPDX-License-Identifier: MIT
package marvin.host.application.vitals.port.in;

import java.util.List;
import java.util.Optional;

import marvin.host.domain.vitals.VitalsCurrent;
import marvin.host.domain.vitals.VitalsMinute;

/** The vital signs for the app (contract C3). */
public interface VitalsQuery {

    /** The current vital signs; empty when there is no vitals sensor. */
    Optional<VitalsCurrent> current();

    /** One row per minute with readings over the last {@code hours} hours, oldest first, the minute in progress last. */
    List<VitalsMinute> minutes(int hours);
}

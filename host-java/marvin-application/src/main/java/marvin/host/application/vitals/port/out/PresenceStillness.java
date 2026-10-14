// SPDX-License-Identifier: MIT
package marvin.host.application.vitals.port.out;

import marvin.host.domain.vitals.Stillness;

/** What the presence context believes about the person now: still, moving, or not known. */
public interface PresenceStillness {

    Stillness now();
}

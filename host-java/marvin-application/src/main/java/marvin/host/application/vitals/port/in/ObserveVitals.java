// SPDX-License-Identifier: MIT
package marvin.host.application.vitals.port.in;

import marvin.host.domain.vitals.VitalsInput;

/** MR60BHA2 readings in, from whichever device sends them. */
public interface ObserveVitals {

    void onReading(VitalsInput reading);

    /**
     * A presence radar (an LD2450) sent targets: the stillness it reports is used by the gate for a while (without
     * one, the radar's own validity and signal quality decide).
     */
    void onPresenceRadar();
}

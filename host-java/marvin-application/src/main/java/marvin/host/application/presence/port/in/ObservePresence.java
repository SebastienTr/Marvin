// SPDX-License-Identifier: MIT
package marvin.host.application.presence.port.in;

import java.util.List;

import marvin.host.domain.presence.TargetSighting;
import marvin.host.domain.presence.VitalsReading;

/** Feeds the brain: what the radars see, in the order the frames arrived. Device clock, microseconds. */
public interface ObservePresence {

    /** One LD2450 frame. {@code simulated}: the device's {@code HELLO} said its data is simulated. */
    void onTargets(long tUs, boolean simulated, List<TargetSighting> targets);

    /** One MR60BHA2 reading. */
    void onVitals(long tUs, boolean simulated, VitalsReading reading);
}

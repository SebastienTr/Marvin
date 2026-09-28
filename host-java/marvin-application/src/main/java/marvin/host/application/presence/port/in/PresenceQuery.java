// SPDX-License-Identifier: MIT
package marvin.host.application.presence.port.in;

import java.util.List;

import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;

/** What the brain currently believes, and what it said recently. */
public interface PresenceQuery {

    PresenceState state();

    /** The recent events, oldest first (at most the brain's {@code maxEvents}). */
    List<PresenceEvent> recentEvents();
}

// SPDX-License-Identifier: MIT
package marvin.host.application.presence.port.out;

import marvin.host.domain.presence.event.PresenceEvent;

/** Where the brain's events go (the robot's face, the app, the store), in order, as soon as they happen. */
public interface PresenceEventPublisher {

    void publish(PresenceEvent event);
}

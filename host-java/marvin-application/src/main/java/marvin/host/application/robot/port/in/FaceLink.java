// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.in;

import marvin.host.domain.presence.event.PresenceEvent;

/**
 * The robot's face, fed with what the brain knows (link.py): {@code FACE_EVENT} as soon as an event
 * happens, {@code FACE_STATE} about 10 times a second, to every connected robot with a screen.
 */
public interface FaceLink {

    /** Forwards one brain event to every screen, right away. */
    void onPresenceEvent(PresenceEvent event);

    /** Sends the current presence state to every screen (the scheduler calls it at 10 Hz). */
    void sendState();
}

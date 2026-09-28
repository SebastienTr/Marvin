// SPDX-License-Identifier: MIT
package marvin.host.application.robot;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.robot.port.in.FaceLink;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.robot.port.out.RobotOutbound;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.FaceState;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.MessageType;

/**
 * The face link (link.py): {@code FACE_EVENT} for each brain event and {@code FACE_STATE} on each
 * {@link #sendState()}, to every connected device whose board has the screen.
 */
public final class FaceLinkService implements FaceLink {
    private final RobotLinkQuery link;
    private final PresenceQuery presence;
    private final RobotOutbound out;
    private final AtomicLong eventsSent = new AtomicLong();
    private final AtomicLong statesSent = new AtomicLong();

    public FaceLinkService(RobotLinkQuery link, PresenceQuery presence, RobotOutbound out) {
        this.link = Objects.requireNonNull(link, "link");
        this.presence = Objects.requireNonNull(presence, "presence");
        this.out = Objects.requireNonNull(out, "out");
    }

    /** The connected devices that have a screen. */
    public List<Device> screens() {
        return link.connected().stream().filter(d -> d.hello().hasScreen()).toList();
    }

    @Override
    public void onPresenceEvent(PresenceEvent event) {
        byte[] payload = HostMessages.faceEvent(event.kind().faceCode());
        for (Device d : screens()) {
            out.send(d, MessageType.FACE_EVENT, payload);
            eventsSent.incrementAndGet();
        }
    }

    @Override
    public void sendState() {
        List<Device> screens = screens();
        if (screens.isEmpty()) {
            return;
        }
        byte[] payload = faceState(presence.state()).encode();
        for (Device d : screens) {
            out.send(d, MessageType.FACE_STATE, payload);
            statesSent.incrementAndGet();
        }
    }

    /** What the face needs from the presence state. */
    public static FaceState faceState(PresenceState s) {
        return new FaceState(s.present(), s.seated(), s.head(), s.position(), s.distanceM(), s.heartRate());
    }

    public long eventsSent() {
        return eventsSent.get();
    }

    public long statesSent() {
        return statesSent.get();
    }
}

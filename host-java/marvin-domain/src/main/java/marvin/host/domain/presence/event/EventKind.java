// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.event;

import java.util.Optional;

/**
 * What the brain can tell the rest of the robot. The wire name is the one the app and the store
 * use; the face code is the {@code FACE_EVENT} byte shared with the firmware (docs/protocol.md).
 */
public enum EventKind {
    ARRIVED("arrived", 1),
    LEFT("left", 2),
    APPROACHED("approached", 3),
    SAT_DOWN("sat_down", 4),
    STOOD_UP("stood_up", 5),
    STILL_LONG("still_long", 6),
    VITALS_ACQUIRED("vitals_acquired", 7),
    VITALS_LOST("vitals_lost", 8);

    private final String wireName;
    private final int faceCode;

    EventKind(String wireName, int faceCode) {
        this.wireName = wireName;
        this.faceCode = faceCode;
    }

    public String wireName() {
        return wireName;
    }

    public int faceCode() {
        return faceCode;
    }

    public static Optional<EventKind> fromWireName(String name) {
        for (EventKind k : values()) {
            if (k.wireName.equals(name)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.event;

/**
 * The brain's current belief about the person in front of the robot, the nearest one (events.py
 * {@code PresenceState}). An immutable snapshot. Device frame, millimetres (see docs/architecture.md).
 *
 * @param tUs          the device clock of the last frame, microseconds
 * @param position     the nearest person, device frame, mm (radar height), or {@code null}
 * @param head         the estimated head position, or {@code null}
 * @param distanceM    horizontal distance to the robot, or {@code null}
 * @param speedCms     smoothed speed, cm/s, positive moving away
 * @param stillS       seconds without significant movement
 * @param seatedS      seconds since {@code sat_down}
 * @param breathRate   per minute, {@code null} when not reliable
 * @param heartRate    per minute, {@code null} when not reliable
 * @param vitalsSensor a vital-signs radar (MR60BHA2) has reported
 * @param simulated    the sensor data comes from a simulated person
 * @param targets      people seen by the LD2450
 */
public record PresenceState(long tUs, boolean present, boolean seated, double[] position, double[] head,
                            Double distanceM, double speedCms, double stillS, double seatedS, Double breathRate,
                            Double heartRate, boolean vitalsSensor, boolean simulated, int targets) {

    /** Nobody, nothing known. */
    public static final PresenceState EMPTY = new PresenceState(0, false, false, null, null, null, 0, 0, 0, null,
            null, false, false, 0);

    public PresenceState {
        position = position == null ? null : position.clone();
        head = head == null ? null : head.clone();
    }

    @Override
    public double[] position() {
        return position == null ? null : position.clone();
    }

    @Override
    public double[] head() {
        return head == null ? null : head.clone();
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

/** How far the current vital signs can be trusted (contract C3, {@code quality}). */
public enum VitalsQuality {
    /** Valid readings of a still person in the radar's range, steady over the gate's window: the medians are shown. */
    GOOD("good"),
    /** Readings, but not trusted yet (too few, the person moves, out of range, the signal is not steady). */
    UNSTABLE("unstable"),
    /** No usable reading. */
    NONE("none");

    private final String wire;

    VitalsQuality(String wire) {
        this.wire = wire;
    }

    /** The API's name. */
    public String wire() {
        return wire;
    }
}

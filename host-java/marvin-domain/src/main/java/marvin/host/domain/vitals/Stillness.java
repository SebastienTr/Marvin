// SPDX-License-Identifier: MIT
package marvin.host.domain.vitals;

/** What the presence radar says about the person the vital signs are read from. */
public enum Stillness {
    /** Present and still. */
    STILL,
    /** Present and moving: vital signs are not trusted. */
    MOVING,
    /** No presence radar, or it does not see the person (a seated, still person can fade from the LD2450). */
    UNKNOWN
}

// SPDX-License-Identifier: MIT
package marvin.host.application.vitals.port.out;

import java.util.List;

import marvin.host.domain.vitals.VitalsMinute;

/** Where the per-minute vital signs are kept. */
public interface VitalsStore {

    /** Stores a minute (the same minute again replaces it). */
    void add(VitalsMinute minute);

    /** Minutes with {@code start <= t < end}, oldest first. */
    List<VitalsMinute> minutes(double start, double end);

    /** Deletes the minutes before {@code t}; returns how many. */
    int deleteBefore(double t);
}

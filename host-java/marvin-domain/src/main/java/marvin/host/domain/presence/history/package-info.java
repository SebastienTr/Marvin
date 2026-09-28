// SPDX-License-Identifier: MIT
/**
 * The presence history (the Python host's {@code ui/stats.py}): brain events as stored with the host's
 * wall clock, the host's own markers ({@code host_started}, {@code robot_offline} ...), and the pure
 * functions that turn them into days: present and seated intervals, sessions, breaks, averages, and the
 * sentences the app shows. No I/O, no clock: time is always passed in.
 */
package marvin.host.domain.presence.history;

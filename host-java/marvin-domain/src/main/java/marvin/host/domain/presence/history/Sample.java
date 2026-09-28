// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.history;

/**
 * One minute of history (store.py {@code samples}): the fraction of it someone was present and seated,
 * and the average breathing and heart rates over the reliable readings ({@code null} when there were
 * too few).
 *
 * @param ts the minute's start, wall clock, Unix seconds
 */
public record Sample(double ts, double present, double seated, Double breath, Double heart) {
}

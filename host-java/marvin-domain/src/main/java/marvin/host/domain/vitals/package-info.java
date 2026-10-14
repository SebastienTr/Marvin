// SPDX-License-Identifier: MIT
/**
 * The vitals context: heart and breathing rates from the MR60BHA2 radar (the robot's simulators and the MR60BHA2
 * bridge), merged into one seat, passed through a quality gate, and kept per minute. Measured, not diagnosed: the
 * values are what the radar reports about a still person in front of it, with a quality saying how far to trust
 * them; nothing here interprets them.
 */
package marvin.host.domain.vitals;

// SPDX-License-Identifier: MIT
package marvin.host.domain.presence;

/**
 * One person seen by the LD2450, as the brain needs it: the position in the device frame (mm) and
 * the radar's own speed reading (cm/s, positive when approaching).
 */
public record TargetSighting(double x, double y, double z, double speedCms) {
}

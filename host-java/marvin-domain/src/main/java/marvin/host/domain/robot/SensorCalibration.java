// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

/**
 * Per-robot calibration (calibration.py): the lidar yaw, the LD2450's x sign and speed sign.
 *
 * @param lidarYawDeg    the lidar angle that points to the front, [0, 360)
 * @param ld2450XSign    1 or -1
 * @param radarSpeedSign 1 or -1: the LD2450 speed is positive when approaching, the brain's when moving away
 */
public record SensorCalibration(double lidarYawDeg, int ld2450XSign, int radarSpeedSign) {

    public static final SensorCalibration DEFAULT = new SensorCalibration(0.0, 1, -1);

    public SensorCalibration {
        if (!Double.isFinite(lidarYawDeg)) {
            throw new IllegalArgumentException("lidar.yaw_deg must be a number");
        }
        if (Math.abs(ld2450XSign) != 1 || Math.abs(radarSpeedSign) != 1) {
            throw new IllegalArgumentException("ld2450.x_sign and ld2450.speed_sign must be 1 or -1");
        }
        lidarYawDeg = Ldrobot.pyMod(lidarYawDeg, 360.0);
    }
}

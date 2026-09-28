// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

/**
 * Sensor mounts and the conversions into the device frame (docs/architecture.md): origin on the body
 * axis at desk level, X to the right as seen by someone facing the robot, Y backwards (the face looks
 * towards -Y), Z up, millimetres.
 *
 * @param lidar  the LDROBOT lidar mount
 * @param radar  the HLK-LD2450 mount
 */
public record Extrinsics(LidarMount lidar, RadarMount radar) {

    /** The mounts of the robot as built (frames.py), before calibration. */
    public static final Extrinsics DEFAULT = new Extrinsics(LidarMount.DEFAULT, RadarMount.DEFAULT);

    /**
     * LDROBOT angles are clockwise seen from above.
     *
     * @param yawDeg the lidar angle that points to the front (-Y)
     * @param minMm  returns closer than this are the robot itself
     */
    public record LidarMount(double zMm, double yawDeg, double minMm, double maxMm) {
        public static final LidarMount DEFAULT = new LidarMount(133.0, 0.0, 30.0, 12000.0);

        public LidarMount withYaw(double yaw) {
            return new LidarMount(zMm, yaw, minMm, maxMm);
        }

        /** Whether a distance is kept (the same test as frames.py, in float32). */
        public boolean keeps(float distanceMm) {
            return distanceMm >= (float) minMm && distanceMm <= (float) maxMm;
        }
    }

    /**
     * HLK-LD2450: x across the radar, y along its boresight; mounted looking at -Y, tilted up.
     *
     * @param xSign -1 if the radar is mounted the other way round
     */
    public record RadarMount(double yMm, double zMm, double tiltDeg, int xSign) {
        public static final RadarMount DEFAULT = new RadarMount(-33.1, 16.4, 10.0, 1);

        public RadarMount withXSign(int sign) {
            return new RadarMount(yMm, zMm, tiltDeg, sign);
        }
    }

    public Extrinsics calibrated(SensorCalibration c) {
        return new Extrinsics(lidar.withYaw(c.lidarYawDeg()), radar.withXSign(c.ld2450XSign()));
    }

    /** One radar target → a device point {x, y, z} (float64, as frames.py). */
    public double[] ld2450ToDevice(double xMm, double yMm) {
        double t = Math.toRadians(radar.tiltDeg());
        return new double[] {radar.xSign() * xMm, radar.yMm() - yMm * Math.cos(t), radar.zMm() + yMm * Math.sin(t)};
    }

    /**
     * Lidar angles and distances → device points, invalid returns dropped. 0° points to -Y, 90° to the
     * robot's right (-X). Float32, as frames.py computes it with numpy (within float32 rounding).
     *
     * @return x, y, z of each kept point, packed
     */
    public float[] lidarToDevice(double[] anglesDeg, float[] distancesMm, int count) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (lidar.keeps(distancesMm[i])) {
                kept++;
            }
        }
        float[] out = new float[3 * kept];
        float z = (float) lidar.zMm();
        float yaw = (float) lidar.yawDeg();
        int j = 0;
        for (int i = 0; i < count; i++) {
            float d = distancesMm[i];
            if (!lidar.keeps(d)) {
                continue;
            }
            double th = Math.toRadians((float) ((float) anglesDeg[i] - yaw));
            out[j++] = (float) (-d * Math.sin(th));
            out[j++] = (float) (-d * Math.cos(th));
            out[j++] = z;
        }
        return out;
    }

    /** Inverse of {@link #lidarToDevice} in the lidar plane: {angle deg, distance mm}. */
    public double[] deviceToLidar(double x, double y) {
        double ang = Math.toDegrees(Math.atan2(-x, -y)) + lidar.yawDeg();
        return new double[] {Ldrobot.pyMod(ang, 360.0), Math.hypot(x, y)};
    }
}

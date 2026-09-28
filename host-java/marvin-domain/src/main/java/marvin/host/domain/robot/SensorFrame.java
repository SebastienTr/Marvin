// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.List;

/**
 * What the robot link hands on after checking a datagram: one per {@code Sink} call of the Python
 * receiver. Times are the device's clock, microseconds.
 */
public sealed interface SensorFrame {

    Device device();

    /** A device said its first {@code HELLO}. */
    record Connected(Device device) implements SensorFrame {
    }

    /**
     * One lidar revolution, stamped with the header clock of the datagram that closed it. Raw: the
     * points are computed by whoever uses it ({@link #points}), off the socket thread.
     *
     * @param count how many of the arrays' entries are used
     */
    record LidarRevolution(Device device, long tUs, double[] anglesDeg, float[] distancesMm, int[] intensities,
                           int count, int speedDps) implements SensorFrame {

        /** The points in the device frame: x, y, z per kept return, packed. */
        public float[] points(Extrinsics extrinsics) {
            return extrinsics.lidarToDevice(anglesDeg, distancesMm, count);
        }

        /** The intensities of the returns {@link #points} keeps. */
        public int[] keptIntensities(Extrinsics extrinsics) {
            int n = 0;
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                if (extrinsics.lidar().keeps(distancesMm[i])) {
                    out[n++] = intensities[i];
                }
            }
            return java.util.Arrays.copyOf(out, n);
        }
    }

    /**
     * One LD2450 frame: the targets, and each one in the device frame ({x, y, z}, mm).
     */
    record RadarTargets(Device device, long tUs, List<Ld2450.Target> targets, List<double[]> points)
            implements SensorFrame {
    }

    record VitalSigns(Device device, long tUs, Vitals vitals) implements SensorFrame {
    }

    record LogLine(Device device, long tUs, String text) implements SensorFrame {
    }

    /** 20 ms of microphone: {@code index} is the sample index of {@code pcm[0]}. */
    record AudioChunk(Device device, long tUs, long index, short[] pcm) implements SensorFrame {
    }

    /**
     * The name of the matching Python {@code Sink} callback, used for statistics: scan, targets,
     * vitals, log, audio, hello.
     */
    default String kind() {
        return switch (this) {
            case Connected c -> "hello";
            case LidarRevolution r -> "scan";
            case RadarTargets t -> "targets";
            case VitalSigns v -> "vitals";
            case LogLine l -> "log";
            case AudioChunk a -> "audio";
        };
    }
}

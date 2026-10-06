// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The sensor mini-views of the app's Robot panel (the Python {@code UISink.scene()}): the last lidar
 * scan reduced to 360 ranges (one per degree), the LD2450 targets with short trails, the MR60BHA2
 * vital signs (rates over the last minutes, waves over the last seconds), and where the servo head looks
 * (the last {@code HEAD}, while fresh).
 *
 * <p>{@link #record} keeps references and appends to short queues (O(1)); the work happens in
 * {@link #view}, when someone looks. The scan reduction is cached per scan. Times are the host's
 * monotonic seconds, passed in. Top view convention: {@code right} is to the robot's right, {@code fwd}
 * in front of it (device frame: right = -X, fwd = -Y); lidar bearings clockwise from the front.
 *
 * <p>With several devices, the last scan and the last targets win, except that real sensors beat simulated ones:
 * while a device with real data (its {@code HELLO} without the simulated flag: a robot with real sensors, the USB
 * sensor bridge) has sent a scan or targets in the last {@link #PREFER_REAL_S} seconds, a simulated device's are
 * not shown. The real lidar on the desk is not interleaved with the simulated room of a bench board.
 * Thread-safe.
 */
public final class SensorScene {
    public static final double TRAIL_S = 5.0;
    public static final double TRAIL_STEP_S = 0.2;
    public static final double WAVES_S = 15.0;
    public static final double RATES_S = 5 * 60.0;
    public static final int LIDAR_BINS = 360;
    /** HLK-LD2450 field of view, for drawing. */
    public static final int RADAR_HALF_ANGLE_DEG = 60;
    public static final int RADAR_RANGE_CM = 600;
    /** How long a real sensor's scan or targets keep a simulated device's out of the views. */
    public static final double PREFER_REAL_S = 2.0;
    /** A {@code HEAD} older than this is not shown (the robot sends one 5 times a second). */
    public static final double HEAD_STALE_S = 2.0;

    private final Extrinsics extrinsics;

    private SensorFrame.LidarRevolution scan;
    private double scanT;
    private boolean scanReal;
    private long scanSeq;
    private long cachedSeq = -1;
    private int[] cachedRanges;
    private int cachedPoints;

    private double targetsT;
    private boolean targetsReal;
    private List<double[]> targets;                 // {right, fwd, speed}
    private final Deque<Trail> trail = new ArrayDeque<>();
    private double vitalsT;
    private Vitals vitals;
    private final Deque<double[]> waves = new ArrayDeque<>();      // {t, breath, heart, valid}
    private final Deque<Double[]> rates = new ArrayDeque<>();      // {t, breath|null, heart|null}
    private Head head;
    private double headT;

    private record Trail(double t, List<double[]> points) {
    }

    public SensorScene(Extrinsics extrinsics) {
        this.extrinsics = extrinsics;
    }

    /** One frame from the link, at monotonic time {@code now}. */
    public synchronized void record(SensorFrame frame, double now) {
        switch (frame) {
            case SensorFrame.LidarRevolution r -> {
                boolean real = !r.device().hello().simulated();
                if (!real && scanReal && now - scanT < PREFER_REAL_S) {
                    return;
                }
                scan = r;
                scanT = now;
                scanReal = real;
                scanSeq++;
            }
            case SensorFrame.RadarTargets t -> {
                boolean real = !t.device().hello().simulated();
                if (!real && targetsReal && now - targetsT < PREFER_REAL_S) {
                    return;
                }
                targetsReal = real;
                List<double[]> pts = new ArrayList<>(t.targets().size());
                for (int i = 0; i < t.targets().size(); i++) {
                    double[] p = t.points().get(i);
                    pts.add(new double[] {-p[0], -p[1], t.targets().get(i).speedCms()});
                }
                targets = pts;
                targetsT = now;
                if (trail.isEmpty() || now - trail.peekLast().t() >= TRAIL_STEP_S) {
                    trail.addLast(new Trail(now, pts));
                    while (trail.size() > (int) (TRAIL_S / TRAIL_STEP_S) + 1) {
                        trail.removeFirst();
                    }
                }
            }
            case SensorFrame.VitalSigns v -> {
                Vitals x = v.vitals();
                vitals = x;
                vitalsT = now;
                waves.addLast(new double[] {now, x.breathWave(), x.heartWave(), x.valid() ? 1 : 0});
                while (waves.size() > 1200) {
                    waves.removeFirst();
                }
                if (rates.isEmpty() || now - rates.peekLast()[0] >= 1.0) {
                    rates.addLast(new Double[] {now, x.valid() ? x.breathRate() : null, x.valid() ? x.heartRate() : null});
                    while (rates.size() > (int) RATES_S + 5) {
                        rates.removeFirst();
                    }
                }
            }
            case SensorFrame.HeadPose h -> {
                head = h.head();
                headT = now;
            }
            default -> {
            }
        }
    }

    /** The mini-views at monotonic time {@code now}; {@code history}: include the vital sign rates. */
    public synchronized View view(double now, boolean history) {
        Lidar lidar = null;
        if (scan != null) {
            if (cachedSeq != scanSeq) {
                float[] pts = scan.points(extrinsics);
                cachedRanges = reduceScan(pts, LIDAR_BINS);
                cachedPoints = pts.length / 3;
                cachedSeq = scanSeq;
            }
            lidar = new Lidar(round(now - scanT, 2), cachedRanges.clone(), cachedPoints);
        }
        List<int[]> tg = new ArrayList<>();
        if (targets != null && now - targetsT < 2.0) {
            for (double[] p : targets) {
                tg.add(new int[] {(int) Math.rint(p[0] / 10), (int) Math.rint(p[1] / 10), (int) p[2]});
            }
        }
        List<double[]> tr = new ArrayList<>();
        for (Trail t : trail) {
            if (now - t.t() <= TRAIL_S) {
                for (double[] p : t.points()) {
                    tr.add(new double[] {round(now - t.t(), 1), Math.rint(p[0] / 10), Math.rint(p[1] / 10)});
                }
            }
        }
        VitalsView vv = null;
        if (vitals != null && now - vitalsT < 10.0) {
            Vitals v = vitals;
            List<double[]> w = new ArrayList<>();
            for (double[] x : waves) {
                if (x[3] != 0 && now - x[0] <= WAVES_S) {
                    w.add(new double[] {round(now - x[0], 2), round(x[1], 3), round(x[2], 3)});
                }
            }
            List<Double[]> r = null;
            if (history) {
                r = new ArrayList<>();
                for (Double[] x : rates) {
                    if (now - x[0] <= RATES_S) {
                        r.add(new Double[] {Math.rint(now - x[0]), x[1] == null ? null : round(x[1], 1),
                                x[2] == null ? null : round(x[2], 1)});
                    }
                }
            }
            vv = new VitalsView(v.valid(), v.valid() ? round(v.breathRate(), 1) : null,
                    v.valid() ? round(v.heartRate(), 1) : null,
                    v.distanceMm() != 0 ? (int) Math.rint(v.distanceMm() / 10.0) : null, w, r);
        }
        HeadView hv = null;
        if (head != null && now - headT <= HEAD_STALE_S) {
            Head h = head;
            hv = new HeadView(round(h.yawDeg(), 1), round(h.goalDeg(), 1),
                    h.targetYawDeg() == null ? null : round(h.targetYawDeg(), 1),
                    h.targetDistanceMm() == null ? null : (int) Math.rint(h.targetDistanceMm() / 10.0),
                    Head.modeName(h.mode()), h.tracking(), h.glancing(), h.servoOn(), round(now - headT, 2));
        }
        Double radarAge = targets == null ? null : round(now - targetsT, 2);
        return new View(lidar, tg, tr, vv, hv, radarAge);
    }

    /**
     * @param lidar   {@code null} before the first scan
     * @param targets {right cm, fwd cm, speed cm/s} of the targets seen in the last 2 s
     * @param trail   {age s, right cm, fwd cm}, a point every 0.2 s over the last 5 s
     * @param vitals  {@code null} without a reading in the last 10 s
     * @param head    {@code null} without a {@code HEAD} in the last {@link #HEAD_STALE_S} seconds
     * @param radarAgeS how old the last LD2450 frame shown is (with or without targets), {@code null} before the first
     */
    public record View(Lidar lidar, List<int[]> targets, List<double[]> trail, VitalsView vitals, HeadView head,
                       Double radarAgeS) {
        /** A view without a head or a radar frame. */
        public View(Lidar lidar, List<int[]> targets, List<double[]> trail, VitalsView vitals) {
            this(lidar, targets, trail, vitals, null, null);
        }
    }

    /**
     * Where the servo head looks. Angles in degrees, 0 straight ahead, positive towards the device frame's +X (the
     * robot's left, the right of someone facing it), as {@link Head}.
     *
     * @param angleDeg         the yaw now
     * @param goalDeg          where the head is going
     * @param targetDeg        the nearest target's yaw, {@code null} without one
     * @param targetDistanceCm the nearest target's distance, {@code null} without one
     * @param mode             {@code auto}, {@code manual}, {@code center}, or {@code null} for a mode this host does not know
     * @param ageS             how old the last {@code HEAD} is
     */
    public record HeadView(double angleDeg, double goalDeg, Double targetDeg, Integer targetDistanceCm, String mode,
                           boolean tracking, boolean glancing, boolean servoOn, double ageS) {
    }

    /** @param rangesCm the nearest return per degree, clockwise from the front; 0 where nothing was seen */
    public record Lidar(double ageS, int[] rangesCm, int points) {
    }

    /**
     * @param waves {age s, breathing wave, heart wave} over the last 15 s
     * @param rates {age s, breaths/min or null, beats/min or null} once a second over the last 5 min;
     *              {@code null} when not asked for
     */
    public record VitalsView(boolean valid, Double breathRate, Double heartRate, Integer distanceCm,
                             List<double[]> waves, List<Double[]> rates) {
    }

    /** Device-frame points (x, y, z packed, mm) → the nearest range per bearing, cm; 0 where nothing. */
    public static int[] reduceScan(float[] points, int bins) {
        double[] out = new double[bins];
        java.util.Arrays.fill(out, Double.POSITIVE_INFINITY);
        for (int i = 0; i + 2 < points.length; i += 3) {
            double x = points[i];
            double y = points[i + 1];
            double bearing = Ldrobot.pyMod(Math.toDegrees(Math.atan2(-x, -y)), 360.0);
            int idx = Math.floorMod((long) (bearing * bins / 360.0), bins);
            out[idx] = Math.min(out[idx], Math.hypot(x, y));
        }
        int[] cm = new int[bins];
        for (int i = 0; i < bins; i++) {
            cm[i] = Double.isFinite(out[i]) ? (int) Math.rint(out[i] / 10.0) : 0;
        }
        return cm;
    }

    private static double round(double v, int n) {
        return marvin.host.domain.shared.PyNumbers.round(v, n);
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

import marvin.host.application.presence.port.in.ObservePresence;
import marvin.host.application.robot.port.out.SensorFrameListener;
import marvin.host.domain.presence.TargetSighting;
import marvin.host.domain.presence.VitalsReading;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.Endpoint;
import marvin.host.domain.robot.Environment;
import marvin.host.domain.robot.Ld2450;
import marvin.host.domain.robot.SensorFrame;

/**
 * The robot context's frames, translated into the presence context's inputs: LD2450 targets (in the
 * device frame), MR60BHA2 readings and PIR readings, from every device, into one brain (as the Python host
 * does). The two contexts only meet here, through their ports.
 *
 * <p>A device whose radar data is simulated but whose PIR is real (the bench board with its room sensors) is
 * the presence through its PIR only: its simulated person no longer drives the brain. Until its first
 * {@code ENVIRONMENT} says whether it has a PIR, the room-sensors flag of its {@code HELLO} is enough to hold
 * its simulated radar back (brain.py does the same).
 *
 * <p>Real radar beats simulated radar: while a device with real sensors (a robot, the USB sensor bridge with an
 * LD2450) has sent targets in the last {@link #PREFER_REAL_S} seconds, a simulated device's targets and vital signs
 * do not reach the brain, which follows one person on one clock. The lidar does not reach the brain at all.
 */
public final class PresenceFeed implements SensorFrameListener {
    /** How long a real radar's targets keep a simulated device's out of the brain. */
    public static final double PREFER_REAL_S = 2.0;

    private final ObservePresence presence;
    private final DoubleSupplier monotonic;
    private final Map<Endpoint, Boolean> hasPir = new ConcurrentHashMap<>();
    private volatile double realRadarT = Double.NEGATIVE_INFINITY;

    public PresenceFeed(ObservePresence presence) {
        this(presence, () -> System.nanoTime() / 1e9);
    }

    /** @param monotonic the host's monotonic clock, seconds */
    public PresenceFeed(ObservePresence presence, DoubleSupplier monotonic) {
        this.presence = presence;
        this.monotonic = monotonic;
    }

    @Override
    public void onFrame(SensorFrame frame) {
        switch (frame) {
            case SensorFrame.RadarTargets t -> {
                if (!t.device().hello().simulated()) {
                    realRadarT = monotonic.getAsDouble();
                } else if (realRadarLive()) {
                    return;
                }
                if (pirStandsIn(t.device())) {
                    return;
                }
                List<TargetSighting> seen = new ArrayList<>(t.targets().size());
                for (int i = 0; i < t.targets().size(); i++) {
                    Ld2450.Target target = t.targets().get(i);
                    double[] p = t.points().get(i);
                    seen.add(new TargetSighting(p[0], p[1], p[2], target.speedCms()));
                }
                presence.onTargets(t.tUs(), t.device().hello().simulated(), seen);
            }
            case SensorFrame.VitalSigns v -> {
                if (v.device().hello().simulated() && realRadarLive()) {
                    return;
                }
                if (!pirStandsIn(v.device())) {
                    presence.onVitals(v.tUs(), v.device().hello().simulated(),
                            new VitalsReading(v.vitals().valid(), v.vitals().breathRate(), v.vitals().heartRate()));
                }
            }
            case SensorFrame.RoomReading r -> {
                Environment e = r.environment();
                hasPir.put(r.device().endpoint(), e.pir());
                if (e.pir()) {
                    presence.onMotion(r.tUs(), e.simulated(), e.motion(), e.warmingUp());
                }
            }
            default -> {
                // the brain does not use the lidar, logs, audio or buttons
            }
        }
    }

    private boolean realRadarLive() {
        return monotonic.getAsDouble() - realRadarT < PREFER_REAL_S;
    }

    /** A simulated device with room sensors: its PIR, not its simulated radar, is the presence. */
    private boolean pirStandsIn(Device d) {
        return d.hello().simulated() && d.hello().hasEnvironment() && hasPir.getOrDefault(d.endpoint(), true);
    }
}

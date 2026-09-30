// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public final class PresenceFeed implements SensorFrameListener {
    private final ObservePresence presence;
    private final Map<Endpoint, Boolean> hasPir = new ConcurrentHashMap<>();

    public PresenceFeed(ObservePresence presence) {
        this.presence = presence;
    }

    @Override
    public void onFrame(SensorFrame frame) {
        switch (frame) {
            case SensorFrame.RadarTargets t -> {
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

    /** A simulated device with room sensors: its PIR, not its simulated radar, is the presence. */
    private boolean pirStandsIn(Device d) {
        return d.hello().simulated() && d.hello().hasEnvironment() && hasPir.getOrDefault(d.endpoint(), true);
    }
}

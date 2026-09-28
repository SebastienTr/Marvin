// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.ArrayList;
import java.util.List;

import marvin.host.application.presence.port.in.ObservePresence;
import marvin.host.application.robot.port.out.SensorFrameListener;
import marvin.host.domain.presence.TargetSighting;
import marvin.host.domain.presence.VitalsReading;
import marvin.host.domain.robot.Ld2450;
import marvin.host.domain.robot.SensorFrame;

/**
 * The robot context's frames, translated into the presence context's inputs: LD2450 targets (in the
 * device frame) and MR60BHA2 readings, from every device, into one brain (as the Python host does).
 * The two contexts only meet here, through their ports.
 */
public final class PresenceFeed implements SensorFrameListener {
    private final ObservePresence presence;

    public PresenceFeed(ObservePresence presence) {
        this.presence = presence;
    }

    @Override
    public void onFrame(SensorFrame frame) {
        switch (frame) {
            case SensorFrame.RadarTargets t -> {
                List<TargetSighting> seen = new ArrayList<>(t.targets().size());
                for (int i = 0; i < t.targets().size(); i++) {
                    Ld2450.Target target = t.targets().get(i);
                    double[] p = t.points().get(i);
                    seen.add(new TargetSighting(p[0], p[1], p[2], target.speedCms()));
                }
                presence.onTargets(t.tUs(), t.device().hello().simulated(), seen);
            }
            case SensorFrame.VitalSigns v -> presence.onVitals(v.tUs(), v.device().hello().simulated(),
                    new VitalsReading(v.vitals().valid(), v.vitals().breathRate(), v.vitals().heartRate()));
            default -> {
                // the brain does not use the lidar, logs or audio
            }
        }
    }
}

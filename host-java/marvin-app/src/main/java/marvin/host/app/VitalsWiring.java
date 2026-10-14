// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.application.vitals.port.out.VitalsStore;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.robot.port.out.SensorFrameListener;
import marvin.host.application.vitals.VitalsService;
import marvin.host.application.vitals.port.in.ObserveVitals;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.SensorFrame;
import marvin.host.domain.robot.Vitals;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.vitals.GateConfig;
import marvin.host.domain.vitals.Stillness;
import marvin.host.domain.vitals.VitalsInput;

/**
 * The vitals context (contract C3): the robot link's {@code VITALS} (and LD2450 targets, for stillness) into the
 * vitals service, the presence brain's stillness into its gate, its per-minute history into the {@code presence}
 * schema, a tick once a second. The web adapter's {@code VitalsController} serves it and puts it on the stream.
 */
@Configuration(proxyBeanMethods = false)
public class VitalsWiring {
    private static final Logger log = LoggerFactory.getLogger("marvin.vitals");
    /** Still for this long by the presence radar: still enough for vital signs. */
    static final double STILL_FOR_S = 1.0;

    @Bean
    public VitalsService vitalsService(VitalsStore store, PresenceQuery presence, Clocks clocks,
                                       @Value("${marvin.vitals.keep-days:90}") double keepDays) {
        return new VitalsService(GateConfig.DEFAULT, store, () -> stillness(presence.state()), clocks, keepDays);
    }

    /** Present and still for a second: still; present otherwise: moving; not seen: unknown (the radar decides). */
    static Stillness stillness(PresenceState s) {
        if (!s.present() || s.motionPresence()) {
            return Stillness.UNKNOWN;
        }
        return s.stillS() >= STILL_FOR_S ? Stillness.STILL : Stillness.MOVING;
    }

    /** The robot link's frames the vitals context needs (HostWiring adds it to the link's listeners). */
    @Bean
    public VitalsFeed vitalsFeed(VitalsService vitals) {
        return new VitalsFeed(vitals);
    }

    @Bean
    public VitalsTicker vitalsTicker(VitalsService vitals) {
        return new VitalsTicker(vitals);
    }

    /**
     * {@code VITALS} from every device, each one a source (one seat: the gate picks one), and the fact that an LD2450
     * is sending targets (its stillness then counts).
     */
    public static final class VitalsFeed implements SensorFrameListener {
        private final ObserveVitals vitals;

        public VitalsFeed(ObserveVitals vitals) {
            this.vitals = vitals;
        }

        @Override
        public void onFrame(SensorFrame frame) {
            switch (frame) {
                case SensorFrame.VitalSigns v -> {
                    Vitals x = v.vitals();
                    vitals.onReading(new VitalsInput(v.device().endpoint().toString(), v.device().hello().simulated(),
                            x.valid(), x.breathRate(), x.heartRate(), x.distanceMm(), x.steadyIfReported()));
                }
                case SensorFrame.RadarTargets t -> vitals.onPresenceRadar();
                default -> {
                    // nothing else concerns the vital signs
                }
            }
        }
    }

    /** {@link VitalsService#tick()} once a second. */
    public static final class VitalsTicker implements SmartLifecycle {
        private final VitalsService vitals;
        private ScheduledExecutorService timer;

        VitalsTicker(VitalsService vitals) {
            this.vitals = vitals;
        }

        private void tick() {
            try {
                vitals.tick();
            } catch (RuntimeException e) {
                log.warn("vitals tick failed", e);
            }
        }

        @Override
        public synchronized void start() {
            timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("vitals-ticker").daemon().factory());
            timer.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        }

        @Override
        public synchronized void stop() {
            if (timer != null) {
                timer.shutdownNow();
                timer = null;
            }
        }

        @Override
        public synchronized boolean isRunning() {
            return timer != null;
        }
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import marvin.host.adapter.robot.mvrec.RecordingReplay;
import marvin.host.adapter.robot.mvrec.RecordingWriter;
import marvin.host.application.robot.port.in.FaceLink;
import marvin.host.application.robot.port.in.MonitorRobotLink;

/**
 * Starts and stops the robot link with the host: the UDP socket (or a replay), the recording and the
 * tap, and the two clocks of the link: the device monitor's tick (1 Hz) and the face state (10 Hz).
 */
public final class RobotLinkRunner implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RobotLinkRunner.class);

    private final UdpRobotLink link;
    private final RobotLinkProperties props;
    private final MonitorRobotLink monitor;
    private final FaceLink face;
    private final String version;
    private ScheduledExecutorService scheduler;
    private Thread replay;
    private volatile boolean running;

    public RobotLinkRunner(UdpRobotLink link, RobotLinkProperties props, MonitorRobotLink monitor, FaceLink face,
                           String version) {
        this.link = link;
        this.props = props;
        this.monitor = monitor;
        this.face = face;
        this.version = version;
    }

    @Override
    public synchronized void start() {
        if (running || !props.enabled()) {
            return;
        }
        if (!props.record().isEmpty()) {
            Path p = Path.of(props.record());
            link.record(RecordingWriter.create(p, version, Map.of()));
            log.info("recording the robot's datagrams to {}", p);
        }
        if (!props.tap().isEmpty()) {
            InetSocketAddress target = parseTap(props.tap());
            link.tap(new DatagramTap(target));
            log.info("forwarding the robot's datagrams to {} (marvin-host run --port {} --no-ui)", target,
                    target.getPort());
        }
        if (props.replay().isEmpty()) {
            link.start(props.bind(), props.port());
        } else {
            startReplay(Path.of(props.replay()));
        }
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofPlatform().name("robot-link-clock").daemon().factory());
        scheduler.scheduleAtFixedRate(guard("tick", monitor::tick), 1000, 1000, TimeUnit.MILLISECONDS);
        long period = Math.max(1, Math.round(1e6 / props.faceStateHz()));
        scheduler.scheduleAtFixedRate(guard("face state", face::sendState), period, period, TimeUnit.MICROSECONDS);
        running = true;
    }

    private void startReplay(Path file) {
        link.dispatcher().start();
        double speed = props.replaySpeed();
        log.info("replaying {} at {} instead of listening for robots{}", file,
                speed <= 0 ? "full speed" : "x" + speed, props.replayLoop() ? ", looping" : "");
        replay = Thread.ofPlatform().name("robot-replay").daemon().start(() -> {
            try {
                RecordingReplay.replay(file, link::receive, speed, props.replayLoop(), () -> !running);
            } catch (RuntimeException e) {
                log.error("replay of {} failed: {}", file, e.getMessage());
            }
        });
    }

    static InetSocketAddress parseTap(String tap) {
        int i = tap.lastIndexOf(':');
        if (i < 0) {
            return new InetSocketAddress("127.0.0.1", Integer.parseInt(tap));
        }
        return new InetSocketAddress(tap.substring(0, i), Integer.parseInt(tap.substring(i + 1)));
    }

    private static Runnable guard(String what, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("robot link {} failed", what, e);
            }
        };
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (replay != null) {
            try {
                replay.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        link.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Late start, early stop: after the database and before the web server stops. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE / 2;
    }
}

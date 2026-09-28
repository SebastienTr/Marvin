// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.SmartLifecycle;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.presence.port.in.PresenceHistory;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.settings.port.in.ManageSettings;

/**
 * The app's two Server-Sent Event streams, as the Python host sends them:
 * <ul>
 * <li>{@code /api/stream}: {@code hello} (the settings), {@code today}, {@code devices}, then the live
 * {@code state} twice a second, and whatever the hub carries ({@code event}, followed by {@code today};
 * {@code log}, {@code settings}, {@code devices}, the voice's messages);</li>
 * <li>{@code /api/robot/stream}, open only while the Robot panel is on screen: the sensor
 * {@code scene} 4 times a second (with the vital sign history once a second) and the {@code devices}.</li>
 * </ul>
 * Each client runs on its own (virtual) thread; streams end when the host stops.
 */
@RestController
public class StreamController implements SmartLifecycle {
    static final long STATE_PERIOD_MS = 500;
    static final long SCENE_PERIOD_MS = 250;

    private final EventHub hub;
    private final LiveState live;
    private final PresenceHistory history;
    private final ManageSettings settings;
    private final RobotLinkQuery robot;
    private volatile boolean running;

    public StreamController(EventHub hub, LiveState live, PresenceHistory history, ManageSettings settings,
                            RobotLinkQuery robot) {
        this.hub = hub;
        this.live = live;
        this.history = history;
        this.settings = settings;
        this.robot = robot;
    }

    @GetMapping("/api/stream")
    public void stream(HttpServletResponse rs) throws IOException {
        OutputStream out = open(rs);
        BlockingQueue<EventHub.Message> q = hub.subscribe();
        try {
            sse(out, "hello", Map.of("settings", settings.current().toMap()), 3000);
            sse(out, "today", Views.day(history.today()), 0);
            sse(out, "devices", Views.devices(robot.devices()), 0);
            long nextState = 0;
            while (running) {
                long now = System.nanoTime();
                if (now - nextState >= 0) {
                    sse(out, "state", live.snapshot(), 0);
                    nextState = now + TimeUnit.MILLISECONDS.toNanos(STATE_PERIOD_MS);
                }
                long wait = Math.max(TimeUnit.MILLISECONDS.toNanos(10), nextState - System.nanoTime());
                EventHub.Message m = q.poll(wait, TimeUnit.NANOSECONDS);
                if (m == null) {
                    continue;
                }
                sse(out, m.kind(), m.payload(), 0);
                if (m.kind().equals("event")) {
                    sse(out, "today", Views.day(history.today()), 0);
                }
            }
        } catch (IOException e) {
            // the client went away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            hub.unsubscribe(q);
        }
    }

    @GetMapping("/api/robot/stream")
    public void robotStream(HttpServletResponse rs) throws IOException {
        OutputStream out = open(rs);
        try {
            sse(out, "hello", Map.of(), 3000);
            long n = 0;
            while (running) {
                boolean full = n % 4 == 0;
                Map<String, Object> p = ApiController.robotPayload(robot, full);
                sse(out, "scene", p.get("scene"), 0);
                if (full) {
                    sse(out, "devices", p.get("devices"), 0);
                }
                n++;
                Thread.sleep(SCENE_PERIOD_MS);
            }
        } catch (IOException e) {
            // the client went away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static OutputStream open(HttpServletResponse rs) throws IOException {
        rs.setStatus(200);
        rs.setHeader("Content-Type", "text/event-stream");
        rs.setHeader("Cache-Control", "no-store");
        rs.setHeader("X-Accel-Buffering", "no");
        rs.flushBuffer();
        return rs.getOutputStream();
    }

    private static void sse(OutputStream out, String kind, Object payload, int retry) throws IOException {
        String msg = (retry > 0 ? "retry: " + retry + "\n" : "") + "event: " + kind + "\ndata: " + PyJson.write(payload)
                + "\n\n";
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ------------------------------------------------------------------ lifecycle: streams end first

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 10;          // stopped before the web server's graceful shutdown
    }
}

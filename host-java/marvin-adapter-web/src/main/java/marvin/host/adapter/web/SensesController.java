// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.SmartLifecycle;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.conversation.port.in.TaskControl;
import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.vision.port.in.Camera;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.VoiceSnapshot;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.shared.Clocks;

/**
 * The Senses screen (docs/ui.md, "Senses"; the Java host only): everything Marvin sees, hears and feels, for an
 * engineer.
 * <ul>
 * <li>{@code GET /api/senses}: the devices and their links, the sensor scene, what Marvin {@code feels} (presence, its
 * source and the evidence, the room, the vital signs), {@code hears} (the voice's state or why it hears nothing, the
 * engines, the last level and the ears' last diagnostics), {@code thinks} (the model, the reasoning levels, the running
 * task, the last answer's stages), and {@code sees} (the camera, what the vision model said last);</li>
 * <li>{@code GET /api/senses/stream}, open only while the screen is on screen: the {@code scene} 5 times a second, the
 * {@code devices} and a {@code senses} summary (feels, hears, thinks, the last look) once a second, and as they come the voice's
 * {@code level} (at most 20 a second), the ears' {@code hearing} diagnostics, {@code utterance}, {@code voice},
 * {@code camera} and {@code task}.</li>
 * </ul>
 * Nothing is stored; the stream costs nothing while nobody looks.
 */
@RestController
public class SensesController implements SmartLifecycle {
    static final long SCENE_PERIOD_MS = 200;
    static final long SUMMARY_PERIOD_MS = 1000;
    /** At most 20 levels a second (the voice sends about 16). */
    static final long LEVEL_MIN_MS = 50;
    /** The reasoning levels ask the model server for the model's description: not every second. */
    static final long THINKING_PERIOD_MS = 30_000;
    static final Set<String> LIVE = Set.of("level", "hearing", "utterance", "voice", "camera", "task");

    private final RobotLinkQuery robot;
    private final PresenceQuery presence;
    private final VoiceControl voice;
    private final Camera camera;
    private final TaskControl tasks;
    private final EventHub hub;
    private final Clocks clocks;
    private volatile boolean running;

    public SensesController(RobotLinkQuery robot, PresenceQuery presence, VoiceControl voice, Camera camera,
                            TaskControl tasks, EventHub hub, Clocks clocks) {
        this.robot = robot;
        this.presence = presence;
        this.voice = voice;
        this.camera = camera;
        this.tasks = tasks;
        this.hub = hub;
        this.clocks = clocks;
    }

    @GetMapping("/api/senses")
    public ResponseEntity<byte[]> senses() {
        List<ConversationEntry> recent = voice.recent(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", clocks.wallSeconds());
        m.put("devices", Views.devices(robot.devices()));
        m.put("scene", Views.scene(robot.scene(false)));
        m.put("feels", feels());
        m.put("hears", hears(true));
        Map<String, Object> thinks = thinks(recent);
        thinks.put("reasoning", reasoning());
        thinks.put("task", runningTask());
        m.put("thinks", thinks);
        Map<String, Object> sees = new LinkedHashMap<>();
        sees.put("camera", CameraController.payload(camera.status()));
        sees.put("look", Senses.lastLook(recent));
        m.put("sees", sees);
        return Responses.json(m);
    }

    @GetMapping("/api/senses/stream")
    public void stream(HttpServletResponse rs) throws IOException {
        OutputStream out = StreamController.open(rs);
        BlockingQueue<EventHub.Message> q = hub.subscribe(LIVE::contains);
        try {
            StreamController.sse(out, "hello", Map.of("scene_hz", 1000 / SCENE_PERIOD_MS, "level_hz", 1000 / LEVEL_MIN_MS), 3000);
            long nextScene = 0;
            long nextSummary = 0;
            long nextThinking = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(THINKING_PERIOD_MS);
            long lastLevel = 0;
            Map<String, Object> reasoning = null;
            while (running) {
                long now = System.nanoTime();
                if (now - nextScene >= 0) {
                    StreamController.sse(out, "scene", Views.scene(robot.scene(false)), 0);
                    nextScene = now + TimeUnit.MILLISECONDS.toNanos(SCENE_PERIOD_MS);
                }
                if (now - nextSummary >= 0) {
                    StreamController.sse(out, "devices", Views.devices(robot.devices()), 0);
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("t", clocks.wallSeconds());
                    s.put("feels", feels());
                    s.put("hears", hears(false));
                    List<ConversationEntry> recent = voice.recent(0);
                    Map<String, Object> thinks = thinks(recent);
                    if (now - nextThinking >= 0) {
                        reasoning = reasoning();
                        nextThinking = now + TimeUnit.MILLISECONDS.toNanos(THINKING_PERIOD_MS);
                    }
                    if (reasoning != null) {
                        thinks.put("reasoning", reasoning);
                    }
                    s.put("thinks", thinks);
                    s.put("look", Senses.lastLook(recent));
                    StreamController.sse(out, "senses", s, 0);
                    nextSummary = now + TimeUnit.MILLISECONDS.toNanos(SUMMARY_PERIOD_MS);
                }
                long wait = Math.max(TimeUnit.MILLISECONDS.toNanos(5), Math.min(nextScene, nextSummary) - System.nanoTime());
                EventHub.Message m = q.poll(wait, TimeUnit.NANOSECONDS);
                while (m != null) {
                    if (m.kind().equals("level")) {
                        long t = System.nanoTime();
                        if (t - lastLevel >= TimeUnit.MILLISECONDS.toNanos(LEVEL_MIN_MS)) {
                            StreamController.sse(out, m.kind(), m.payload(), 0);
                            lastLevel = t;
                        }
                    } else {
                        StreamController.sse(out, m.kind(), m.payload(), 0);
                    }
                    m = q.poll();
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

    private Map<String, Object> feels() {
        List<DeviceStatus> devices = robot.devices();
        return Senses.feels(presence.state(), devices, robot.room());
    }

    private Map<String, Object> hears(boolean recent) {
        boolean available = voice.available();
        VoiceSnapshot v = voice.snapshot();
        return Senses.hears(available, available ? "" : voice.unavailableReason(), v, voice.hearing(),
                available ? voice.appSettings() : Map.of(), recent);
    }

    private Map<String, Object> thinks(List<ConversationEntry> recent) {
        VoiceSnapshot v = voice.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", v.model());
        m.put("status", voice.available() && "on".equals(v.state()) ? v.status() : "off");
        m.put("last_answer", Senses.lastAnswer(recent));
        return m;
    }

    /** How hard the model thinks, in the conversation and on tasks, and whether it can ({@code null} if unknown). */
    private Map<String, Object> reasoning() {
        try {
            Map<String, Object> s = tasks.settings();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("settings", s.get("settings"));
            m.put("model", s.get("model"));
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The task running or waiting, the newest, as lists show it; {@code null} when none. */
    private Map<String, Object> runningTask() {
        try {
            for (Task t : tasks.tasks(5)) {
                if (!t.status().finished()) {
                    return t.toSummaryMap();
                }
            }
        } catch (RuntimeException e) {
            // the database is away: no task shown
        }
        return null;
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
        return Integer.MAX_VALUE - 10;
    }
}

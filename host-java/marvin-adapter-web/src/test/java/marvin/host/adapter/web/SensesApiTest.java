// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.VoiceSnapshot;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.robot.LinkStats;
import marvin.host.domain.robot.SensorScene;
import marvin.host.domain.robot.event.RoomState;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.JsonText;

/**
 * The Senses screen's API: {@code GET /api/senses} behind the real access filter on stand-in ports, its stream (the
 * scene, the devices and the summary on their clocks, the voice's levels throttled, the ears' diagnostics), and the
 * evidence it gives for presence.
 */
class SensesApiTest {

    static final class Robot implements RobotLinkQuery {
        volatile boolean radar;
        volatile RoomState room;
        volatile SensorScene.HeadView head;

        @Override
        public List<DeviceStatus> devices() {
            Map<String, Double> rates = new LinkedHashMap<>(Map.of("datagrams", 180.0, "scans", 10.0, "radar", radar ? 9.0 : 0.0,
                    "vitals", 0.0, "audio", 0.0));
            return List.of(new DeviceStatus("aa", "marvin-11016b", Board.Role.USB_SENSORS, "USB sensor bridge (computer)",
                    "usb-0.1.0", null, null, null, 12, false, false, false, false, true, 0.1, 1.8e9, rates, 0.0,
                    new LinkStats.Snapshot(1800, 1800, 0, 0, 0, 0, Map.of()), 2131));
        }

        @Override
        public List<Device> connected() {
            return List.of();
        }

        @Override
        public SensorScene.View scene(boolean history) {
            int[] ranges = new int[SensorScene.LIDAR_BINS];
            Arrays.fill(ranges, 45);
            ranges[61] = 13;                         // a mug, close
            return new SensorScene.View(new SensorScene.Lidar(0.05, ranges, 2131), List.of(), List.of(), null, head, head == null ? null : 0.1);
        }

        @Override
        public RoomState room() {
            return room;
        }

        @Override
        public boolean linked() {
            return true;
        }
    }

    static final class Presence implements PresenceQuery {
        volatile PresenceState state = PresenceState.EMPTY;

        @Override
        public PresenceState state() {
            return state;
        }

        @Override
        public List<PresenceEvent> recentEvents() {
            return List.of();
        }
    }

    static final class Voice implements VoiceControl {
        volatile boolean on = true;
        final List<Map<String, Object>> recentHearings = new ArrayList<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return "";
        }

        @Override
        public String unavailableFix() {
            return "";
        }

        @Override
        public VoiceSnapshot snapshot() {
            return new VoiceSnapshot(on ? "on" : "off", on ? "listening" : "off", false, "", "", "qwen3.8:27b-mlx", true, true,
                    null, false, null, false, on ? "computer" : "", "device");
        }

        @Override
        public Map<String, Object> hearing() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stt", on ? "parakeet tdt-0.6b-v3" : "");
            m.put("tts", on ? "qwen marvin" : "");
            m.put("device", on ? "computer" : "");
            m.put("level", null);
            m.put("recent", List.copyOf(recentHearings));
            return m;
        }

        @Override
        public Map<String, Object> appSettings() {
            return Map.of("llm_model", "qwen3.8:27b-mlx", "stt", "auto", "tts", "auto", "vocabulary", List.of("Marvin"));
        }

        @Override
        public Map<String, Object> updateSettings(Map<String, ?> update) {
            return appSettings();
        }

        @Override
        public List<ConversationEntry> recent(long since) {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("model", "qwen3.8:27b-mlx");
            reply.put("first_word_s", 1.42);
            reply.put("latency", Map.of("endpoint", 0.2, "stt", 0.09, "llm_first_token", 0.61));
            reply.put("tools", List.of(Map.of("name", "look", "ok", true, "seconds", 2.1,
                    "result", "A desk with a laptop and a red mug.")));
            reply.put("memory", Map.of("usage", Map.of("load_s", 0.0)));
            return List.of(new ConversationEntry(1, 1.8e9, "heard", "qu'est-ce que tu vois ?", Map.of()),
                    new ConversationEntry(2, 1.8e9 + 3, "reply", "Je vois un bureau.", reply));
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void rewarm() {
        }

        @Override
        public void clearHistory() {
        }

        @Override
        public void ask(String text) {
        }

        @Override
        public void ask(String text, byte[] image) {
        }

        @Override
        public Map<String, Object> attachImage(byte[] image) {
            return Map.of();
        }

        @Override
        public void removeImage() {
        }

        @Override
        public void listenNow(boolean on) {
        }

        @Override
        public void mute(boolean muted) {
        }

        @Override
        public void stopSpeaking() {
        }

        @Override
        public Map<String, Object> options() {
            return Map.of();
        }
    }

    static final Clocks CLOCK = new Clocks() {
        @Override
        public double wallSeconds() {
            return 1.8e9;
        }

        @Override
        public double monotonicSeconds() {
            return 100.0;
        }
    };

    final Robot robot = new Robot();
    final Presence presence = new Presence();
    final Voice voice = new Voice();
    final EventHub hub = new EventHub();
    final SensesController senses = new SensesController(robot, presence, voice, new CameraApiTest.FakeCamera(),
            new TaskApiTest.FakeTasks(), hub, CLOCK);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(senses).setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    @SuppressWarnings("unchecked")
    Map<String, Object> getSenses() throws Exception {
        String body = mvc.perform(get("/api/senses").header("Host", "localhost:8765")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return (Map<String, Object>) JsonText.parse(body);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSceneSaysWhereTheHeadLooksAndIsNullWithoutOne() throws Exception {
        Map<String, Object> scene = (Map<String, Object>) getSenses().get("scene");
        assertThat(scene).containsEntry("head", null).containsKeys("lidar", "targets", "trail", "vitals", "radar");
        assertThat((Map<String, Object>) scene.get("radar")).containsEntry("half_angle_deg", 60L).containsEntry("age_s", null);
        robot.head = new SensorScene.HeadView(-21.5, -24.0, -25.3, 132, "auto", true, false, true, 0.12);
        scene = (Map<String, Object>) getSenses().get("scene");
        assertThat((Map<String, Object>) scene.get("head")).containsEntry("angle_deg", -21.5).containsEntry("goal_deg", -24.0)
                .containsEntry("target_deg", -25.3).containsEntry("target_distance_cm", 132L).containsEntry("mode", "auto")
                .containsEntry("tracking", true).containsEntry("glancing", false).containsEntry("servo_on", true)
                .containsEntry("age_s", 0.12);
        assertThat((Map<String, Object>) scene.get("radar")).containsEntry("age_s", 0.1);
        robot.head = new SensorScene.HeadView(0.0, 0.0, null, null, null, false, true, false, 1.5);
        scene = (Map<String, Object>) getSenses().get("scene");
        assertThat((Map<String, Object>) scene.get("head")).containsEntry("target_deg", null).containsEntry("mode", null)
                .containsEntry("angle_deg", 0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everythingMarvinSeesHearsAndFeelsInOneSnapshot() throws Exception {
        voice.recentHearings.add(Map.of("kind", "utterance", "uid", 3, "text", "Marvin, bonjour.", "outcome", "heard"));
        Map<String, Object> s = getSenses();
        assertThat(s).containsKeys("t", "devices", "scene", "feels", "hears", "thinks", "sees");
        assertThat((List<Map<String, Object>>) s.get("devices")).extracting(d -> d.get("role")).containsExactly("usb-sensors");
        Map<String, Object> lidar = (Map<String, Object>) ((Map<String, Object>) s.get("scene")).get("lidar");
        assertThat(lidar).containsEntry("points", 2131L);
        Map<String, Object> hears = (Map<String, Object>) s.get("hears");
        assertThat(hears).containsEntry("status", "listening").containsEntry("why", "").containsEntry("stt", "parakeet tdt-0.6b-v3")
                .containsEntry("device", "computer").containsEntry("language", "auto");
        assertThat((List<Map<String, Object>>) hears.get("recent")).extracting(h -> h.get("text")).containsExactly("Marvin, bonjour.");
        Map<String, Object> thinks = (Map<String, Object>) s.get("thinks");
        assertThat(thinks).containsEntry("model", "qwen3.8:27b-mlx").containsEntry("status", "listening").containsEntry("task", null);
        assertThat((Map<String, Object>) thinks.get("last_answer")).containsEntry("first_word_s", 1.42).containsEntry("load_s", 0.0);
        assertThat((Map<String, Object>) ((Map<String, Object>) thinks.get("reasoning")).get("settings")).containsKey("reasoning");
        Map<String, Object> sees = (Map<String, Object>) s.get("sees");
        assertThat((Map<String, Object>) sees.get("look")).containsEntry("ok", true)
                .containsEntry("text", "A desk with a laptop and a red mug.");
        assertThat((Map<String, Object>) sees.get("camera")).containsEntry("source", "computer");
        Map<String, Object> feels = (Map<String, Object>) s.get("feels");
        Map<String, Object> p = (Map<String, Object>) feels.get("presence");
        assertThat(p).containsEntry("source", "none");
        assertThat((List<String>) p.get("reasoning")).first().asString().contains("the lidar sees shapes");
        assertThat((Map<String, Object>) feels.get("sensors")).containsEntry("lidar", true).containsEntry("radar", false)
                .containsEntry("camera", false);
        assertThat(feels).containsEntry("room", null);
        // another device needs the key, as for the whole API
        mvc.perform(get("/api/senses").header("Host", "192.168.1.10:8765").with(r -> {
            r.setRemoteAddr("192.168.1.20");
            return r;
        })).andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("unchecked")
    void whenTheVoiceIsOffItSaysWhy() throws Exception {
        voice.on = false;
        Map<String, Object> hears = (Map<String, Object>) getSenses().get("hears");
        assertThat(hears).containsEntry("state", "off").containsEntry("status", "off").containsEntry("stt", "")
                .containsEntry("why", "The voice is off: turn it on in Talk.");
    }

    @Test
    void presenceSaysWhereItComesFromAndWhy() {
        Senses.Sensors radar = new Senses.Sensors(true, true, false, false, false, false);
        PresenceState seated = new PresenceState(1, true, true, null, null, 0.82, 0.0, 12.0, 840.0, null, null, false,
                false, 1);
        assertThat(Senses.presenceSource(seated, radar)).isEqualTo("radar");
        assertThat(Senses.reasoning(seated, "radar", radar, null)).containsSubsequence(
                "Present: the radar sees one person, the nearest at 0.82 m.", "Seated for 14 min: still for 12 s.",
                "The camera is never used for presence: it takes a picture only when asked.");
        RoomState pirRoom = new RoomState(3.0, "marvin-bench", false, true, false, true, 4.0, false, null, null, false, null);
        PresenceState motion = new PresenceState(1, true, false, null, null, null, 0.0, 4.0, 0.0, null, null, false, false,
                0, true);
        Senses.Sensors pir = Senses.Sensors.of(List.of(), pirRoom);
        assertThat(pir.pir()).isTrue();
        assertThat(Senses.presenceSource(motion, pir)).isEqualTo("pir");
        assertThat(Senses.reasoning(motion, "pir", pir, pirRoom).getFirst())
                .isEqualTo("Present from motion alone: the PIR saw movement 4 s ago.");
        assertThat(Senses.presenceSource(PresenceState.EMPTY, new Senses.Sensors(false, false, false, false, false, false)))
                .isEqualTo("none");
    }

    @Test
    void theStreamSendsTheSceneTheSummaryTheLevelsThrottledAndTheEarsDiagnostics() throws Exception {
        senses.start();
        MockHttpServletResponse rs = new MockHttpServletResponse();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                senses.stream(rs);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // the main stream does not carry the ears' diagnostics; the Senses stream does
        BlockingQueue<EventHub.Message> main = hub.subscribe();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (hub.size() < 2 && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        for (int i = 0; i < 10; i++) {                  // ten levels at once: at most 20 a second get through
            hub.onVoice("level", Map.of("mic", 0.5, "speech", true, "gated", false, "dbfs", -24.0, "t", 1.8e9));
        }
        hub.onVoice("hearing", Map.of("kind", "turn", "uid", 4, "complete", true, "probability", 0.93));
        hub.onVoice("partial", Map.of("uid", 4, "text", "Marvin"));          // not for this screen
        Thread.sleep(1300);
        senses.stop();
        t.join(3000);
        String out = rs.getContentAsString(StandardCharsets.UTF_8);
        List<String> events = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.startsWith("event: ")) {
                events.add(line.substring(7));
            }
        }
        assertThat(out).startsWith("retry: 3000\nevent: hello\n");
        assertThat(events).contains("scene", "devices", "senses", "level", "hearing").doesNotContain("partial");
        assertThat(events.stream().filter("scene"::equals).count()).isBetween(5L, 9L);   // 5 a second
        assertThat(events.stream().filter("level"::equals).count()).isLessThan(10L);
        assertThat(rs.getContentType()).isEqualTo("text/event-stream");
        assertThat(main.stream().map(EventHub.Message::kind)).contains("level", "partial").doesNotContain("hearing");
        hub.unsubscribe(main);
        assertThat(hub.size()).isZero();
    }
}

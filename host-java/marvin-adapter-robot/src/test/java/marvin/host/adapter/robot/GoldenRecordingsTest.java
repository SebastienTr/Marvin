// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import marvin.host.adapter.robot.mvrec.RecordingReader;
import marvin.host.application.presence.PresenceService;
import marvin.host.domain.presence.BrainConfig;
import marvin.host.domain.presence.TargetSighting;
import marvin.host.domain.presence.VitalsReading;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.LinkStats;
import marvin.host.domain.robot.RobotLinkProcessor;
import marvin.host.domain.robot.SensorFrame;
import tools.jackson.databind.JsonNode;

/**
 * Replays each golden recording (made by the Python simulator, marvin-contracts/tools/recordings.py)
 * through the Java link and brain, and compares with what the Python host made of it: the same
 * events at the same device times with the same details and data, the same presence state at the
 * end of every device tick (floats within 1e-6), the same lidar revolutions, the same counters.
 */
class GoldenRecordingsTest {
    private static final double STATE_EPS = 1e-6;
    private static final double DATA_EPS = 1e-9;

    /** What the Python generator's {@code Capture} sink records. */
    static final class Capture {
        final List<PresenceEvent> events = new ArrayList<>();
        final List<Map<String, Object>> states = new ArrayList<>();
        final List<long[]> scans = new ArrayList<>();
        final List<Device> hellos = new ArrayList<>();
        final PresenceService presence;
        final Extrinsics extrinsics;
        Long tick;

        Capture(BrainConfig config, Extrinsics extrinsics) {
            this.presence = new PresenceService(config, events::add);
            this.extrinsics = extrinsics;
        }

        void flush(Long tUs) {
            if (tick != null && !tick.equals(tUs)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("state", presence.state());
                s.put("tick_t_us", tick);
                states.add(s);
            }
            tick = tUs;
        }

        void accept(SensorFrame f) {
            switch (f) {
                case SensorFrame.Connected c -> hellos.add(c.device());
                case SensorFrame.RadarTargets t -> {
                    flush(t.tUs());
                    List<TargetSighting> seen = new ArrayList<>();
                    for (int i = 0; i < t.targets().size(); i++) {
                        double[] p = t.points().get(i);
                        seen.add(new TargetSighting(p[0], p[1], p[2], t.targets().get(i).speedCms()));
                    }
                    presence.onTargets(t.tUs(), t.device().hello().simulated(), seen);
                }
                case SensorFrame.VitalSigns v -> {
                    flush(v.tUs());
                    presence.onVitals(v.tUs(), v.device().hello().simulated(),
                            new VitalsReading(v.vitals().valid(), v.vitals().breathRate(), v.vitals().heartRate()));
                }
                case SensorFrame.LidarRevolution r -> {
                    float[] pts = r.points(extrinsics);
                    int[] inten = r.keptIntensities(extrinsics);
                    long sum = 0;
                    for (int i : inten) {
                        sum += i;
                    }
                    assertThat(inten.length * 3).isEqualTo(pts.length);
                    scans.add(new long[] {r.tUs(), inten.length, r.speedDps(), sum});
                }
                default -> {
                }
            }
        }
    }

    static BrainConfig brainConfig(JsonNode c) {
        return new BrainConfig(c.get("arrive_confirm_s").asDouble(), c.get("leave_after_s").asDouble(),
                c.get("approach_m").asDouble(), c.get("approach_rearm_m").asDouble(),
                c.get("position_tau_s").asDouble(), c.get("speed_tau_s").asDouble(), c.get("radar_speed_sign").asInt(),
                c.get("still_window_s").asDouble(), c.get("still_move_mm").asDouble(),
                c.get("still_speed_cms").asDouble(), c.get("sit_max_m").asDouble(), c.get("sit_still_s").asDouble(),
                c.get("stand_move_mm").asDouble(), c.get("stand_away_m").asDouble(),
                c.get("stand_speed_cms").asDouble(), c.get("stand_speed_s").asDouble(),
                c.get("still_long_s").asDouble(), c.get("vitals_acquire_s").asDouble(),
                c.get("vitals_lose_s").asDouble(), c.get("breath_range").get(0).asDouble(),
                c.get("breath_range").get(1).asDouble(), c.get("heart_range").get(0).asDouble(),
                c.get("heart_range").get(1).asDouble(), c.get("head_z_seated_mm").asDouble(),
                c.get("head_z_standing_mm").asDouble(), c.get("clock_reset_s").asDouble(),
                c.get("max_events").asInt());
    }

    @TestFactory
    Stream<DynamicTest> everyScenarioReplaysLikeThePythonHost() {
        JsonNode index = Golden.json("recordings/index.json");
        BrainConfig config = brainConfig(index.get("brain_config"));
        assertThat(config).isEqualTo(BrainConfig.DEFAULT);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode sc : index.get("scenarios")) {
            tests.add(DynamicTest.dynamicTest(sc.get("name").asString(), () -> check(sc, config)));
        }
        return tests.stream();
    }

    private void check(JsonNode sc, BrainConfig config) {
        String name = sc.get("name").asString();
        RobotLinkProcessor rx = new RobotLinkProcessor(Extrinsics.DEFAULT);
        Capture cap = new Capture(config, Extrinsics.DEFAULT);
        long datagrams = 0;
        try (RecordingReader reader = new RecordingReader(Golden.open("recordings/" + sc.get("file").asString()),
                name)) {
            for (RecordingReader.Datagram d : reader) {
                datagrams++;
                rx.handle(d.data(), d.from()).frames().forEach(cap::accept);
            }
            assertThat(reader.truncated()).isFalse();
            assertThat(reader.meta().get("scenario")).isEqualTo(name);
        }
        cap.flush(null);
        assertThat(datagrams).isEqualTo(sc.get("datagrams").asLong());

        // events: same kind, device time, detail, data
        List<JsonNode> events = Golden.jsonl("recordings/" + name + ".events.jsonl");
        assertThat(cap.events).as("events").hasSize(events.size());
        for (int i = 0; i < events.size(); i++) {
            JsonNode e = events.get(i);
            PresenceEvent got = cap.events.get(i);
            assertThat(got.kind().wireName()).as("event %d kind", i).isEqualTo(e.get("kind").asString());
            assertThat(got.tUs()).as("event %d t_us", i).isEqualTo(e.get("t_us").asLong());
            assertThat(got.detail()).as("event %d detail", i).isEqualTo(e.get("detail").asString());
            List<String> keys = new ArrayList<>();
            e.get("data").properties().forEach(p -> keys.add(p.getKey()));
            assertThat(got.data().keySet()).as("event %d data", i).containsExactlyElementsOf(keys);
            e.get("data").properties().forEach(p -> assertThat(got.data().get(p.getKey()))
                    .isCloseTo(p.getValue().asDouble(), within(DATA_EPS)));
        }

        // states at the end of every device tick
        List<JsonNode> states = Golden.jsonl("recordings/" + name + ".states.jsonl");
        assertThat(cap.states).as("states").hasSize(states.size());
        for (int i = 0; i < states.size(); i++) {
            compareState(i, states.get(i), (PresenceState) cap.states.get(i).get("state"),
                    (Long) cap.states.get(i).get("tick_t_us"));
        }

        // lidar revolutions
        List<JsonNode> scans = Golden.jsonl("recordings/" + name + ".scans.jsonl");
        assertThat(cap.scans).as("scans").hasSize(scans.size());
        for (int i = 0; i < scans.size(); i++) {
            JsonNode s = scans.get(i);
            assertThat(cap.scans.get(i)).as("scan %d", i).containsExactly(s.get("t_us").asLong(),
                    s.get("points").asLong(), s.get("speed_dps").asLong(), s.get("intensity_sum").asLong());
        }

        // devices, HELLOs and link counters
        assertThat(cap.hellos).hasSize(sc.get("hellos").size());
        for (int i = 0; i < cap.hellos.size(); i++) {
            JsonNode h = sc.get("hellos").get(i);
            Device d = cap.hellos.get(i);
            assertThat(d.endpoint().toString()).isEqualTo(h.get("address").asString());
            assertThat(d.name()).isEqualTo(h.get("device_name").asString());
            assertThat(d.hello().board()).isEqualTo(h.get("board").asInt());
            assertThat(d.hello().firmware()).isEqualTo(h.get("firmware").asString());
            assertThat(d.hello().simulated()).isEqualTo(h.get("simulated").asBoolean());
        }
        JsonNode devs = sc.get("receiver").get("devices");
        assertThat(rx.devices()).hasSize(devs.size());
        for (JsonNode dj : devs) {
            Device d = rx.devices().stream().filter(x -> x.endpoint().toString().equals(dj.get("address").asString()))
                    .findFirst().orElseThrow();
            LinkStats.Snapshot s = d.stats().snapshot();
            JsonNode es = dj.get("stats");
            assertThat(new long[] {s.datagrams(), s.lidarPackets(), s.radarFrames(), s.lost(), s.crcErrors(), s.bad()})
                    .as("%s counters", name)
                    .containsExactly(es.get("datagrams").asLong(), es.get("lidar_packets").asLong(),
                            es.get("radar_frames").asLong(), es.get("lost").asLong(), es.get("crc_errors").asLong(),
                            es.get("bad").asLong());
        }
    }

    private static void compareState(int i, JsonNode e, PresenceState s, long tick) {
        String at = "state " + i + " (tick " + e.get("tick_t_us").asLong() + ")";
        assertThat(tick).as(at).isEqualTo(e.get("tick_t_us").asLong());
        assertThat(s.tUs()).as(at + " t_us").isEqualTo(e.get("t_us").asLong());
        assertThat(s.present()).as(at + " present").isEqualTo(e.get("present").asBoolean());
        assertThat(s.seated()).as(at + " seated").isEqualTo(e.get("seated").asBoolean());
        vec(at + " position", e.get("position"), s.position());
        vec(at + " head", e.get("head"), s.head());
        num(at + " distance_m", e.get("distance_m"), s.distanceM());
        num(at + " speed_cms", e.get("speed_cms"), s.speedCms());
        num(at + " still_s", e.get("still_s"), s.stillS());
        num(at + " seated_s", e.get("seated_s"), s.seatedS());
        num(at + " breath_rate", e.get("breath_rate"), s.breathRate());
        num(at + " heart_rate", e.get("heart_rate"), s.heartRate());
        assertThat(s.vitalsSensor()).as(at + " vitals_sensor").isEqualTo(e.get("vitals_sensor").asBoolean());
        assertThat(s.simulated()).as(at + " simulated").isEqualTo(e.get("simulated").asBoolean());
        assertThat(s.targets()).as(at + " targets").isEqualTo(e.get("targets").asInt());
    }

    private static void vec(String what, JsonNode e, double[] got) {
        if (e.isNull()) {
            assertThat(got).as(what).isNull();
            return;
        }
        assertThat(got).as(what).isNotNull().hasSize(3);
        for (int k = 0; k < 3; k++) {
            assertThat(got[k]).as(what + "[" + k + "]").isCloseTo(e.get(k).asDouble(), within(STATE_EPS));
        }
    }

    private static void num(String what, JsonNode e, Double got) {
        if (e.isNull()) {
            assertThat(got).as(what).isNull();
        } else {
            assertThat(got).as(what).isNotNull().isCloseTo(e.asDouble(), within(STATE_EPS));
        }
    }
}

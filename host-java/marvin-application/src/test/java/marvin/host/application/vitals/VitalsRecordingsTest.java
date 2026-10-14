// SPDX-License-Identifier: MIT
package marvin.host.application.vitals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import marvin.host.application.vitals.VitalsServiceTest.FakeClocks;
import marvin.host.application.vitals.VitalsServiceTest.MemoryStore;
import marvin.host.domain.presence.Brain;
import marvin.host.domain.presence.TargetSighting;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.Endpoint;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.Ld2450;
import marvin.host.domain.robot.RobotLinkProcessor;
import marvin.host.domain.robot.SensorFrame;
import marvin.host.domain.robot.Vitals;
import marvin.host.domain.shared.JsonText;
import marvin.host.domain.vitals.GateConfig;
import marvin.host.domain.vitals.Stillness;
import marvin.host.domain.vitals.VitalsCurrent;
import marvin.host.domain.vitals.VitalsInput;
import marvin.host.domain.vitals.VitalsMinute;

/**
 * The MR60BHA2 recordings (golden/recordings/mr60.json, made by marvin-contracts/tools/mr60_recordings.py) replayed
 * through the protocol decoder, the presence brain (for the robot's LD2450) and the vitals service, as the host wires
 * them (VitalsWiring): the quality, rates, distance and source at each expected moment, and the minutes.
 */
class VitalsRecordingsTest {

    record Datagram(double t, Endpoint from, byte[] data) {
    }

    /** The datagrams of a {@code .mvrec(.gz)} (host/marvin_host/record.py, version 1). */
    static List<Datagram> read(String name) throws IOException {
        try (InputStream raw = open("recordings/" + name);
             DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            byte[] head = in.readNBytes(10);
            assertThat(new String(head, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("MVREC");
            in.skipNBytes(ByteBuffer.wrap(head, 6, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            Map<Integer, Endpoint> ids = new HashMap<>();
            List<Datagram> out = new ArrayList<>();
            while (true) {
                byte[] rh;
                try {
                    rh = in.readNBytes(11);
                } catch (EOFException e) {
                    break;
                }
                if (rh.length < 11) {
                    break;
                }
                ByteBuffer b = ByteBuffer.wrap(rh).order(ByteOrder.LITTLE_ENDIAN);
                int kind = b.get() & 0xff;
                long tUs = b.getLong();
                byte[] body = in.readNBytes(b.getShort() & 0xffff);
                ByteBuffer bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                if (kind == 1) {
                    String[] a = new String(body, 2, body.length - 2, StandardCharsets.UTF_8).split(":");
                    ids.put(bb.getShort() & 0xffff, new Endpoint(a[0], Integer.parseInt(a[1])));
                } else if (kind == 2) {
                    byte[] d = new byte[body.length - 2];
                    System.arraycopy(body, 2, d, 0, d.length);
                    out.add(new Datagram(tUs / 1e6, ids.get(bb.getShort() & 0xffff), d));
                }
            }
            return out;
        }
    }

    static InputStream open(String path) {
        InputStream in = VitalsRecordingsTest.class.getResourceAsStream("/marvin/contracts/golden/" + path);
        assertThat(in).as(path).isNotNull();
        return in;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> scenario(String name) throws IOException {
        try (InputStream in = open("recordings/mr60.json")) {
            Map<String, Object> index = (Map<String, Object>) JsonText.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return ((List<Map<String, Object>>) index.get("scenarios")).stream()
                    .filter(s -> s.get("name").equals(name)).findFirst().orElseThrow();
        }
    }

    /** The presence brain's stillness as VitalsWiring maps it. */
    static Stillness stillness(PresenceState s) {
        if (!s.present() || s.motionPresence()) {
            return Stillness.UNKNOWN;
        }
        return s.stillS() >= 1.0 ? Stillness.STILL : Stillness.MOVING;
    }

    static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mr60_desk", "mr60_with_robot"})
    @SuppressWarnings("unchecked")
    void replayedReadingsMeetTheExpectations(String name) throws IOException {
        Map<String, Object> sc = scenario(name);
        FakeClocks clocks = new FakeClocks();
        MemoryStore store = new MemoryStore();
        Brain brain = new Brain();
        VitalsService vitals = new VitalsService(GateConfig.DEFAULT, store, () -> stillness(brain.state()), clocks, 90);
        RobotLinkProcessor link = new RobotLinkProcessor(Extrinsics.DEFAULT);
        List<Map<String, Object>> expect = new ArrayList<>((List<Map<String, Object>>) sc.get("expect"));
        Map<String, String> sources = Map.of("192.0.2.4", "bridge", "192.0.2.1", "robot");
        double nextTick = 0;
        int checked = 0;
        for (Datagram d : read(sc.get("file").toString())) {
            while (!expect.isEmpty() && num(expect.getFirst().get("at_s")) < d.t()) {
                clocks.t = num(expect.getFirst().get("at_s"));
                check(name, expect.removeFirst(), vitals.current().orElse(null), sourceName(vitals, sources));
                checked++;
            }
            clocks.t = d.t();
            while (nextTick <= d.t()) {
                vitals.tick();
                nextTick += 1;
            }
            for (SensorFrame f : link.handle(d.data(), d.from()).frames()) {
                switch (f) {
                    case SensorFrame.VitalSigns v -> {
                        Vitals x = v.vitals();
                        vitals.onReading(new VitalsInput(v.device().endpoint().toString(), v.device().hello().simulated(),
                                x.valid(), x.breathRate(), x.heartRate(), x.distanceMm(), x.steadyIfReported()));
                    }
                    case SensorFrame.RadarTargets t -> {
                        vitals.onPresenceRadar();
                        List<TargetSighting> seen = new ArrayList<>();
                        for (int i = 0; i < t.targets().size(); i++) {
                            Ld2450.Target target = t.targets().get(i);
                            double[] p = t.points().get(i);
                            seen.add(new TargetSighting(p[0], p[1], p[2], target.speedCms()));
                        }
                        brain.onTargets(t.tUs(), t.device().hello().simulated(), seen);
                    }
                    default -> {
                    }
                }
            }
        }
        for (Map<String, Object> e : expect) {
            clocks.t = num(e.get("at_s"));
            check(name, e, vitals.current().orElse(null), sourceName(vitals, sources));
            checked++;
        }
        assertThat(checked).isEqualTo(((List<?>) sc.get("expect")).size());
        if (name.equals("mr60_desk")) {
            // 86 s from a whole minute: the first minute is stored, with rates; the second is open
            List<VitalsMinute> minutes = vitals.minutes(24);
            assertThat(minutes).hasSize(2);
            assertThat(minutes.get(0).n()).isBetween(30, 45);
            assertThat(minutes.get(0).hr()).isBetween(60.0, 66.0);
            assertThat(minutes.get(1).n()).isBetween(5, 20);
            assertThat(store.rows).hasSize(1);
        }
    }

    static String sourceName(VitalsService vitals, Map<String, String> names) {
        String s = vitals.source();
        return s == null ? null : names.get(s.substring(0, s.lastIndexOf(':')));
    }

    static void check(String name, Map<String, Object> e, VitalsCurrent c, String source) {
        String at = name + " at " + e.get("at_s") + " s";
        assertThat(c).as(at).isNotNull();
        if (e.containsKey("quality")) {
            assertThat(c.quality().wire()).as(at + " quality").isEqualTo(e.get("quality"));
        }
        if (e.containsKey("source")) {
            assertThat(source).as(at + " source").isEqualTo(e.get("source"));
        }
        if (e.containsKey("hr")) {
            List<?> r = (List<?>) e.get("hr");
            assertThat(c.hr()).as(at + " hr").isBetween(num(r.get(0)), num(r.get(1)));
        }
        if (e.containsKey("br")) {
            List<?> r = (List<?>) e.get("br");
            assertThat(c.br()).as(at + " br").isBetween(num(r.get(0)), num(r.get(1)));
        }
        if (e.containsKey("distance_m")) {
            assertThat(c.distanceM()).as(at + " distance").isCloseTo(num(e.get("distance_m")), within(0.005));
        }
    }
}

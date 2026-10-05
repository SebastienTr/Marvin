// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.robot.AudioIn;
import marvin.host.domain.robot.AudioOut;
import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.Button;
import marvin.host.domain.robot.Environment;
import marvin.host.domain.robot.Extrinsics;
import marvin.host.domain.robot.FaceState;
import marvin.host.domain.robot.Head;
import marvin.host.domain.robot.Header;
import marvin.host.domain.robot.Hello;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.Ld2450;
import marvin.host.domain.robot.Ldrobot;
import marvin.host.domain.robot.ProtocolException;
import marvin.host.domain.robot.ProtocolV1;
import marvin.host.domain.robot.Vitals;
import marvin.host.domain.robot.Wire;
import tools.jackson.databind.JsonNode;

/**
 * Every protocol v1 vector generated from the Python host (golden/protocol/vectors.json), both
 * directions, byte for byte.
 */
class ProtocolVectorsTest {
    private static final JsonNode V = Golden.json("protocol/vectors.json");
    private static final HexFormat HEX = HexFormat.of();
    private static final double EPS = 1e-9;

    @Test
    void constantsMatch() {
        JsonNode c = V.get("constants");
        assertThat(c.get("version").asInt()).isEqualTo(ProtocolV1.VERSION);
        assertThat(c.get("header_size").asInt()).isEqualTo(ProtocolV1.HEADER_SIZE);
        assertThat(c.get("host_port").asInt()).isEqualTo(ProtocolV1.HOST_PORT);
        assertThat(c.get("device_port").asInt()).isEqualTo(ProtocolV1.DEVICE_PORT);
        c.get("boards").properties().forEach(e -> assertThat(Board.name(Integer.parseInt(e.getKey())))
                .isEqualTo(e.getValue().asString()));
        c.get("lidar_models").properties().forEach(e -> assertThat(Board.lidarModel(Integer.parseInt(e.getKey())))
                .isEqualTo(e.getValue().asString()));
        List<Integer> screens = new ArrayList<>();
        c.get("screen_boards").forEach(n -> screens.add(n.asInt()));
        assertThat(Board.SCREEN_BOARDS).containsExactlyInAnyOrderElementsOf(screens);
        assertThat(c.get("flags").get("simulated").asInt()).isEqualTo(ProtocolV1.FLAG_SIMULATED);
        assertThat(c.get("flags").get("camera").asInt()).isEqualTo(ProtocolV1.FLAG_CAMERA);
        assertThat(c.get("flags").get("audio").asInt()).isEqualTo(ProtocolV1.FLAG_AUDIO);
        assertThat(c.get("flags").get("buttons").asInt()).isEqualTo(ProtocolV1.FLAG_BUTTONS);
        assertThat(c.get("flags").get("buzzer").asInt()).isEqualTo(ProtocolV1.FLAG_BUZZER);
        assertThat(c.get("flags").get("face").asInt()).isEqualTo(ProtocolV1.FLAG_FACE);
        assertThat(c.get("flags").get("environment").asInt()).isEqualTo(ProtocolV1.FLAG_ENVIRONMENT);
        JsonNode env = c.get("environment");
        assertThat(env.get("size").asInt()).isEqualTo(Environment.SIZE);
        env.get("sensors").properties().forEach(e -> assertThat(Environment.sensorNames().get(Integer.parseInt(e.getKey())))
                .isEqualTo(e.getValue().asString()));
        assertThat(Environment.sensorNames()).hasSize(env.get("sensors").size());
        JsonNode ef = env.get("flags");
        assertThat(List.of(ef.get("motion").asInt(), ef.get("warming_up").asInt(), ef.get("temperature_valid").asInt(),
                ef.get("humidity_valid").asInt(), ef.get("light_valid").asInt(), ef.get("simulated").asInt()))
                .containsExactly(Environment.MOTION, Environment.WARMING_UP, Environment.TEMPERATURE_VALID,
                        Environment.HUMIDITY_VALID, Environment.LIGHT_VALID, Environment.SIMULATED);
        assertThat(env.get("no_motion").asInt()).isEqualTo(Environment.NO_MOTION);
        JsonNode head = c.get("head");
        assertThat(head.get("size").asInt()).isEqualTo(Head.SIZE);
        head.get("modes").properties().forEach(e -> assertThat(Head.modeName(Integer.parseInt(e.getKey())))
                .isEqualTo(e.getValue().asString()));
        assertThat(Head.modeNames()).hasSize(head.get("modes").size());
        JsonNode hf = head.get("flags");
        assertThat(List.of(hf.get("target").asInt(), hf.get("tracking").asInt(), hf.get("glancing").asInt(),
                hf.get("servo_on").asInt()))
                .containsExactly(Head.TARGET, Head.TRACKING, Head.GLANCING, Head.SERVO_ON);
        c.get("buttons").properties().forEach(e -> assertThat(Button.name(Integer.parseInt(e.getKey())))
                .isEqualTo(e.getValue().asString()));
        assertThat(Button.names()).hasSize(c.get("buttons").size());
        assertThat(c.get("button_actions").get(String.valueOf(Button.PRESS)).asString()).isEqualTo("press");
        c.get("face_event_codes").properties().forEach(e -> assertThat(
                EventKind.fromWireName(e.getKey()).orElseThrow().faceCode()).isEqualTo(e.getValue().asInt()));
        c.get("sounds").properties().forEach(e -> {
            assertThat(HostMessages.SOUNDS.get(e.getKey())).isEqualTo(e.getValue().get("id").asInt());
            assertThat(HostMessages.SOUND_DURATIONS_MS.get(e.getKey())).isEqualTo(e.getValue().get("duration_ms").asInt());
        });
        assertThat(c.get("audio").get("in_samples").asInt()).isEqualTo(ProtocolV1.AUDIO_IN_SAMPLES);
        assertThat(c.get("audio").get("out_max_samples").asInt()).isEqualTo(ProtocolV1.AUDIO_OUT_MAX_SAMPLES);
        int[] table = Ldrobot.crcTableCopy();
        for (int i = 0; i < 256; i++) {
            assertThat(table[i]).isEqualTo(c.get("ldrobot").get("crc_table").get(i).asInt());
        }
        JsonNode lidar = c.get("extrinsics").get("lidar");
        assertThat(Extrinsics.LidarMount.DEFAULT).isEqualTo(new Extrinsics.LidarMount(lidar.get("z_mm").asDouble(),
                lidar.get("yaw_deg").asDouble(), lidar.get("min_mm").asDouble(), lidar.get("max_mm").asDouble()));
        JsonNode radar = c.get("extrinsics").get("ld2450");
        assertThat(Extrinsics.RadarMount.DEFAULT).isEqualTo(new Extrinsics.RadarMount(radar.get("y_mm").asDouble(),
                radar.get("z_mm").asDouble(), radar.get("tilt_deg").asDouble(), radar.get("x_sign").asInt()));
    }

    @TestFactory
    Stream<DynamicTest> robotToHost() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vec : V.get("robot_to_host")) {
            tests.add(DynamicTest.dynamicTest(vec.get("name").asString(), () -> checkRobotToHost(vec)));
        }
        return tests.stream();
    }

    private void checkRobotToHost(JsonNode vec) {
        byte[] d = HEX.parseHex(vec.get("datagram").asString());
        Header h = Wire.header(d);
        JsonNode eh = vec.get("header");
        assertThat(h).isEqualTo(new Header(eh.get("type").asInt(), eh.get("seq").asLong(), eh.get("t_us").asLong()));
        byte[] payload = Wire.payload(d);
        assertThat(HEX.formatHex(payload)).isEqualTo(vec.get("payload").asString());
        assertThat(Wire.pack(h.type(), h.seq(), h.tUs(), payload)).isEqualTo(d);
        JsonNode e = vec.get("decoded");
        switch (vec.get("type").asInt()) {
            case 0x01 -> {
                Hello hello = Hello.decode(payload);
                assertThat(HEX.formatHex(hello.deviceId())).isEqualTo(e.get("device_id").asString());
                assertThat(hello.board()).isEqualTo(e.get("board").asInt());
                assertThat(Board.name(hello.board())).isEqualTo(e.get("board_name").asString());
                assertThat(hello.flags()).isEqualTo(e.get("flags").asInt());
                assertThat(hello.simulated()).isEqualTo(e.get("simulated").asBoolean());
                assertThat(hello.hasCamera()).isEqualTo(e.get("has_camera").asBoolean());
                assertThat(hello.hasAudio()).isEqualTo(e.get("has_audio").asBoolean());
                assertThat(hello.hasButtons()).isEqualTo(e.get("has_buttons").asBoolean());
                assertThat(hello.hasBuzzer()).isEqualTo(e.get("has_buzzer").asBoolean());
                assertThat(hello.hasScreen()).isEqualTo(e.get("has_face").asBoolean());
                assertThat(hello.hasEnvironment()).isEqualTo(e.get("has_environment").asBoolean());
                assertThat(hello.rssi()).isEqualTo(e.get("rssi").asInt());
                assertThat(hello.uptimeMs()).isEqualTo(e.get("uptime_ms").asLong());
                assertThat(hello.firmware()).isEqualTo(e.get("firmware").asString());
                assertThat(hello.deviceName()).isEqualTo(e.get("device_name").asString());
                assertThat(hello.encode()).isEqualTo(payload);
            }
            case 0x02 -> {
                assertThat(payload[0] & 0xff).isEqualTo(e.get("model").asInt());
                assertThat(Board.lidarModel(payload[0] & 0xff)).isEqualTo(e.get("model_name").asString());
                List<Integer> offsets = Ldrobot.split(1, payload.length);
                assertThat(offsets).hasSize(e.get("packets").size());
                for (int i = 0; i < offsets.size(); i++) {
                    JsonNode ep = e.get("packets").get(i);
                    int off = offsets.get(i);
                    if (ep.has("error")) {
                        assertThatThrownBy(() -> Ldrobot.parse(payload, off, Ldrobot.SIZE))
                                .isInstanceOf(ProtocolException.class).hasMessage("CRC mismatch");
                        assertThat(Ldrobot.crc8(payload, off, off + Ldrobot.SIZE - 1))
                                .isEqualTo(ep.get("crc_expected").asInt());
                        continue;
                    }
                    Ldrobot.Packet p = Ldrobot.parse(payload, off, Ldrobot.SIZE);
                    assertThat(p.speedDps()).isEqualTo(ep.get("speed_dps").asInt());
                    assertThat(p.timestampMs()).isEqualTo(ep.get("timestamp_ms").asInt());
                    assertThat(payload[off + Ldrobot.SIZE - 1] & 0xff).isEqualTo(ep.get("crc").asInt());
                    int[] dist = new int[Ldrobot.POINTS];
                    for (int k = 0; k < Ldrobot.POINTS; k++) {
                        assertThat(p.anglesDeg()[k]).as("angle %d", k)
                                .isEqualTo(ep.get("angles_deg").get(k).asDouble());   // bit for bit
                        dist[k] = ep.get("distances_mm").get(k).asInt();
                        assertThat((int) p.distancesMm()[k]).isEqualTo(dist[k]);
                        assertThat(p.intensities()[k]).isEqualTo(ep.get("intensities").get(k).asInt());
                    }
                    byte[] rebuilt = Ldrobot.build(p.speedDps(), p.anglesDeg()[0], p.anglesDeg()[Ldrobot.POINTS - 1],
                            dist, p.intensities(), p.timestampMs());
                    byte[] original = java.util.Arrays.copyOfRange(payload, off, off + Ldrobot.SIZE);
                    assertThat(rebuilt).isEqualTo(original);
                }
            }
            case 0x03 -> {
                if (e.has("error")) {
                    assertThatThrownBy(() -> Ld2450.parse(payload)).isInstanceOf(ProtocolException.class)
                            .hasMessage("not an LD2450 frame");
                    return;
                }
                List<Ld2450.Target> ts = Ld2450.parse(payload);
                assertThat(ts).hasSize(e.get("targets").size());
                for (int i = 0; i < ts.size(); i++) {
                    JsonNode et = e.get("targets").get(i);
                    Ld2450.Target t = ts.get(i);
                    assertThat(t).isEqualTo(new Ld2450.Target(et.get("x_mm").asInt(), et.get("y_mm").asInt(),
                            et.get("speed_cms").asInt(), et.get("resolution_mm").asInt()));
                    double[] p = Extrinsics.DEFAULT.ld2450ToDevice(t.xMm(), t.yMm());
                    for (int k = 0; k < 3; k++) {
                        assertThat(p[k]).isCloseTo(et.get("device_mm").get(k).asDouble(), within(EPS));
                    }
                }
                assertThat(Ld2450.build(ts)).isEqualTo(payload);
            }
            case 0x04 -> assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo(e.get("text").asString());
            case 0x05 -> {
                Vitals v = Vitals.decode(payload);
                assertThat(v.valid()).isEqualTo(e.get("valid").asBoolean());
                assertThat(v.breathRate()).isCloseTo(e.get("breath_rate").asDouble(), within(EPS));
                assertThat(v.heartRate()).isCloseTo(e.get("heart_rate").asDouble(), within(EPS));
                assertThat(v.breathWave()).isCloseTo(e.get("breath_wave").asDouble(), within(EPS));
                assertThat(v.heartWave()).isCloseTo(e.get("heart_wave").asDouble(), within(EPS));
                assertThat(v.distanceMm()).isEqualTo(e.get("distance_mm").asInt());
                assertThat(v.encode()).isEqualTo(payload);
            }
            case 0x06 -> {
                if (e.has("error")) {
                    assertThatThrownBy(() -> AudioIn.decode(payload)).isInstanceOf(ProtocolException.class)
                            .hasMessage("bad AUDIO_IN length");
                    return;
                }
                AudioIn a = AudioIn.decode(payload);
                assertThat(a.index()).isEqualTo(e.get("index").asLong());
                assertThat(a.pcm()).hasSize(e.get("samples").asInt());
                for (int k = 0; k < e.get("first").size(); k++) {
                    assertThat(a.pcm()[k]).isEqualTo((short) e.get("first").get(k).asInt());
                }
                int n = e.get("last").size();
                for (int k = 0; k < n; k++) {
                    assertThat(a.pcm()[a.pcm().length - n + k]).isEqualTo((short) e.get("last").get(k).asInt());
                }
                assertThat(a.encode()).isEqualTo(payload);
            }
            case 0x07 -> {
                if (e.has("error")) {
                    assertThatThrownBy(() -> Button.decode(payload)).isInstanceOf(ProtocolException.class)
                            .hasMessage("BUTTON too short");
                    return;
                }
                Button b = Button.decode(payload);
                assertThat(b.button()).isEqualTo(e.get("button").asInt());
                assertThat(b.action()).isEqualTo(e.get("action").asInt());
                assertThat(Button.name(b.button())).isEqualTo(e.get("name").isNull() ? null : e.get("name").asString());
                assertThat(b.encode()).isEqualTo(java.util.Arrays.copyOf(payload, Button.SIZE));
            }
            case 0x08 -> {
                if (e.has("error")) {
                    assertThatThrownBy(() -> Environment.decode(payload)).isInstanceOf(ProtocolException.class)
                            .hasMessage("ENVIRONMENT too short");
                    return;
                }
                Environment r = Environment.decode(payload);
                assertThat(r.pir()).isEqualTo(e.get("pir").asBoolean());
                assertThat(r.climate()).isEqualTo(e.get("climate").asBoolean());
                assertThat(r.lightSensor()).isEqualTo(e.get("light_sensor").asBoolean());
                assertThat(r.motion()).isEqualTo(e.get("motion").asBoolean());
                assertThat(r.warmingUp()).isEqualTo(e.get("warming_up").asBoolean());
                assertThat(r.simulated()).isEqualTo(e.get("simulated").asBoolean());
                assertThat(r.temperatureC()).isEqualTo(e.get("temperature_c").isNull() ? null : e.get("temperature_c").asDouble());
                assertThat(r.humidity()).isEqualTo(e.get("humidity").isNull() ? null : e.get("humidity").asDouble());
                assertThat(r.light()).isEqualTo(e.get("light").isNull() ? null : e.get("light").asDouble());
                assertThat(r.lightRaw()).isEqualTo(e.get("light_raw").isNull() ? null : e.get("light_raw").asInt());
                assertThat(r.motionAgeS()).isEqualTo(e.get("motion_age_s").isNull() ? null : e.get("motion_age_s").asInt());
                if (payload.length == Environment.SIZE) {
                    assertThat(r.encode()).as("re-encoded").isEqualTo(payload);
                }
            }
            case 0x09 -> {
                if (e.has("error")) {
                    assertThatThrownBy(() -> Head.decode(payload)).isInstanceOf(ProtocolException.class)
                            .hasMessage("HEAD too short");
                    return;
                }
                Head pose = Head.decode(payload);
                assertThat(pose.mode()).isEqualTo(e.get("mode").asInt());
                assertThat(Head.modeName(pose.mode())).isEqualTo(e.get("mode_name").isNull() ? null : e.get("mode_name").asString());
                assertThat(pose.yawDeg()).isCloseTo(e.get("yaw_deg").asDouble(), within(1e-9));
                assertThat(pose.goalDeg()).isCloseTo(e.get("goal_deg").asDouble(), within(1e-9));
                if (e.get("target_yaw_deg").isNull()) {
                    assertThat(pose.targetYawDeg()).isNull();
                } else {
                    assertThat(pose.targetYawDeg()).isCloseTo(e.get("target_yaw_deg").asDouble(), within(1e-9));
                }
                assertThat(pose.targetDistanceMm()).isEqualTo(e.get("target_distance_mm").isNull() ? null
                        : e.get("target_distance_mm").asInt());
                assertThat(pose.tracking()).isEqualTo(e.get("tracking").asBoolean());
                assertThat(pose.glancing()).isEqualTo(e.get("glancing").asBoolean());
                assertThat(pose.servoOn()).isEqualTo(e.get("servo_on").asBoolean());
                if (payload.length == Head.SIZE && Head.modeName(pose.mode()) != null) {
                    assertThat(pose.encode()).as("re-encoded").isEqualTo(payload);
                }
            }
            default -> throw new AssertionError("no check for type " + vec.get("type"));
        }
    }

    @TestFactory
    Stream<DynamicTest> hostToRobot() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vec : V.get("host_to_robot")) {
            tests.add(DynamicTest.dynamicTest(vec.get("name").asString(), () -> checkHostToRobot(vec)));
        }
        return tests.stream();
    }

    private void checkHostToRobot(JsonNode vec) {
        JsonNode e = vec.get("decoded");
        int type = vec.get("type").asInt();
        if (!vec.has("datagram")) {           // encode must refuse it
            assertThat(type).isEqualTo(0x84);
            assertThatThrownBy(() -> new AudioOut(0, 0, new short[e.get("samples").asInt()]).encode())
                    .isInstanceOf(ProtocolException.class)
                    .hasMessage(e.get("error").asString().replace("ProtocolError: ", ""));
            return;
        }
        byte[] d = HEX.parseHex(vec.get("datagram").asString());
        byte[] expected = Wire.payload(d);
        byte[] ours = switch (type) {
            case 0x81 -> {
                assertThat(HostMessages.hostAck(e.get("host_clock_us").asLong())).isEqualTo(d);
                assertThat(HostMessages.hostAckClock(expected)).isEqualTo(e.get("host_clock_us").asLong());
                yield expected;
            }
            case 0x82 -> {
                FaceState in = face(e.get("input"));
                FaceState back = FaceState.decode(expected);
                FaceState want = face(e.get("decoded"));
                assertThat(back.present()).isEqualTo(want.present());
                assertThat(back.seated()).isEqualTo(want.seated());
                assertThat(back.head()).isEqualTo(want.head());
                assertThat(back.position()).isEqualTo(want.position());
                assertThat(back.distanceM()).isEqualTo(want.distanceM());
                assertThat(back.heartRate()).isEqualTo(want.heartRate());
                yield in.encode();
            }
            case 0x83 -> HostMessages.faceEvent(EventKind.fromWireName(e.get("event").asString()).orElseThrow().faceCode());
            case 0x84 -> {
                AudioOut a = AudioOut.decode(expected);
                assertThat(a.stream()).isEqualTo(e.get("stream").asInt());
                assertThat(a.index()).isEqualTo(e.get("index").asLong());
                assertThat(a.pcm()).hasSize(e.get("samples").asInt());
                for (int k = 0; k < e.get("first").size(); k++) {
                    assertThat(a.pcm()[k]).isEqualTo((short) e.get("first").get(k).asInt());
                }
                yield a.encode();
            }
            case 0x85 -> {
                assertThat(expected[1] & 0xff).isEqualTo(e.get("argument").asInt());
                yield HostMessages.audioCtrl(e.get("command").asInt(), e.get("argument_in").asInt());
            }
            case 0x86 -> {
                assertThat(HostMessages.sound(e.get("id").asInt())).isEqualTo(expected);
                yield HostMessages.sound(e.get("sound").asString());
            }
            case 0x87 -> HostMessages.headCtrl(e.get("mode").asInt(), e.get("yaw_deg").asDouble());
            default -> throw new AssertionError("no check for type " + type);
        };
        JsonNode h = vec.get("header");
        assertThat(HEX.formatHex(Wire.pack(type, h.get("seq").asLong(), h.get("t_us").asLong(), ours)))
                .isEqualTo(vec.get("datagram").asString());
    }

    private static FaceState face(JsonNode n) {
        return new FaceState(n.get("present").asBoolean(), n.get("seated").asBoolean(), vec3(n.get("head")),
                vec3(n.get("position")), n.get("distance_m").isNull() ? null : n.get("distance_m").asDouble(),
                n.get("heart_rate").isNull() ? null : n.get("heart_rate").asDouble());
    }

    private static double[] vec3(JsonNode n) {
        return n == null || n.isNull() ? null : new double[] {n.get(0).asDouble(), n.get(1).asDouble(), n.get(2).asDouble()};
    }

    @Test
    void invalidDatagramsAreRefusedWithThePythonMessages() {
        for (JsonNode vec : V.get("invalid")) {
            byte[] d = HEX.parseHex(vec.get("datagram").asString());
            assertThatThrownBy(() -> Wire.header(d)).as(vec.get("name").asString())
                    .isInstanceOf(ProtocolException.class)
                    .hasMessage(vec.get("error").asString().replace("ProtocolError: ", ""));
        }
    }
}

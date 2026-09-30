// SPDX-License-Identifier: MIT
package marvin.host.application.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.robot.Endpoint;
import marvin.host.domain.robot.Hello;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.MessageType;
import marvin.host.domain.robot.ProtocolV1;
import marvin.host.domain.robot.SensorScene;

class BuzzerChimeServiceTest {
    private static final int BENCH = ProtocolV1.FLAG_SIMULATED | ProtocolV1.FLAG_BUTTONS | ProtocolV1.FLAG_BUZZER;

    private record Sent(String device, MessageType type, byte[] payload) {
    }

    private final List<Device> connected = new ArrayList<>();
    private final List<Sent> sent = new ArrayList<>();
    private final BuzzerChimeService chimes = new BuzzerChimeService(new RobotLinkQuery() {
        @Override
        public List<DeviceStatus> devices() {
            return List.of();
        }

        @Override
        public List<Device> connected() {
            return List.copyOf(connected);
        }

        @Override
        public SensorScene.View scene(boolean history) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean linked() {
            return !connected.isEmpty();
        }
    }, (d, type, payload) -> sent.add(new Sent(d.name(), type, payload)));

    private Device device(int board, int flags, int last) {
        Device d = new Device(new Hello(new byte[] {1, 2, 3, 4, 5, (byte) last}, board, flags, -50, 1, "t"),
                new Endpoint("10.0.0." + last, 47101));
        connected.add(d);
        return d;
    }

    @Test
    void chimesGoToTheBuzzersWithoutASpeaker() {
        Device bench = device(Board.ESP32_DEVKIT, BENCH, 1);
        device(Board.XIAO_ESP32S3_SENSE, ProtocolV1.FLAG_AUDIO | ProtocolV1.FLAG_CAMERA, 2);
        device(Board.D1_MINI, ProtocolV1.FLAG_SIMULATED, 3);
        chimes.chime("wake");
        chimes.chime("no such sound");
        assertThat(sent).singleElement().satisfies(s -> {
            assertThat(s.device()).isEqualTo(bench.name());
            assertThat(s.type()).isEqualTo(MessageType.SOUND);
            assertThat(s.payload()).isEqualTo(HostMessages.sound("wake"));
        });
        assertThat(chimes.sent()).isEqualTo(1);
    }

    @Test
    void aChimeForOneDeviceNeedsABuzzerOrASpeaker() {
        Device bench = device(Board.ESP32_DEVKIT, BENCH, 1);
        Device xiao = device(Board.XIAO_ESP32S3_SENSE, ProtocolV1.FLAG_AUDIO, 2);
        Device d1 = device(Board.D1_MINI, ProtocolV1.FLAG_SIMULATED, 3);
        chimes.chime(bench.name(), "error");
        chimes.chime(xiao.name(), "error");
        chimes.chime(d1.name(), "error");
        chimes.chime("marvin-000000", "error");
        assertThat(sent).extracting(Sent::device).containsExactly(bench.name(), xiao.name());
    }
}

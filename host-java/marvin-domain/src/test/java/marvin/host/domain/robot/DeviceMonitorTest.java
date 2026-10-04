// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Rates, loss, online and offline, as the Python UISink computes them. */
class DeviceMonitorTest {
    private final RobotLinkProcessor rx = new RobotLinkProcessor(Extrinsics.DEFAULT);
    private final DeviceMonitor monitor = new DeviceMonitor(Extrinsics.LidarMount.DEFAULT);
    private final Endpoint robot = new Endpoint("192.168.1.40", 47101);
    private final Endpoint bridge = new Endpoint("192.168.1.41", 47101);
    private long seq;

    private void datagram(Endpoint from, MessageType type, byte[] payload, double now) {
        for (SensorFrame f : rx.handle(Wire.pack(type, seq++, 1000, payload), from).frames()) {
            monitor.record(f, now, 1_790_000_000 + now);
        }
    }

    private static Hello hello(int board, int last) {
        return new Hello(new byte[] {1, 2, 3, 4, 5, (byte) last}, board, 0, -62, 61_500, "0.6.0");
    }

    @Test
    void theVitalsBridgeIsADeviceOfItsOwn() {
        datagram(robot, MessageType.HELLO, hello(Board.ESP32_S3_DEVKITC, 1).encode(), 0);
        datagram(bridge, MessageType.HELLO, hello(Board.MR60BHA2_KIT, 2).encode(), 0);
        List<DeviceMonitor.Notice> notes = monitor.tick(0.1);
        assertThat(notes).hasSize(2).allMatch(n -> n instanceof DeviceMonitor.Notice.Connected);
        assertThat(monitor.statuses()).extracting(DeviceStatus::role)
                .containsExactly(Board.Role.ROBOT, Board.Role.VITALS);
        DeviceStatus r = monitor.statuses().get(0);
        assertThat(r.name()).isEqualTo("marvin-040501");
        assertThat(r.screen()).isTrue();
        assertThat(r.rssiBars()).isEqualTo(3);
        assertThat(r.uptimeS()).isEqualTo(62);        // 61.5 rounds to even
        assertThat(r.ip()).isEqualTo("192.168.1.40");
    }

    @Test
    void theUsbSensorBridgeIsADeviceOfItsOwnWithRealData() {
        datagram(robot, MessageType.HELLO, hello(Board.ESP32_DEVKIT, 1).encode(), 0);
        Endpoint usb = new Endpoint("127.0.0.1", 47131);
        datagram(usb, MessageType.HELLO,
                new Hello(new byte[] {0x1a, 0x2b, 0x3c, 0x4d, 0x5e, 0x6f}, Board.USB_BRIDGE, 0, 0, 42_000, "usb-0.1.0").encode(), 0);
        monitor.tick(0.1);
        DeviceStatus s = monitor.statuses().get(1);
        assertThat(s.role()).isEqualTo(Board.Role.USB_SENSORS);
        assertThat(s.role().wireName()).isEqualTo("usb-sensors");
        assertThat(s.role().label()).isEqualTo("USB sensor bridge");
        assertThat(s.board()).isEqualTo("USB sensor bridge (computer)");
        assertThat(s.simulated()).isFalse();
        assertThat(s.rssi()).isNull();                  // no Wi-Fi: no signal bars
        assertThat(s.screen()).isFalse();
        assertThat(s.name()).isEqualTo("marvin-4d5e6f");
    }

    @Test
    void ratesLossAndOffline() {
        datagram(robot, MessageType.HELLO, hello(Board.D1_MINI, 1).encode(), 0);
        monitor.tick(0);
        for (int i = 1; i <= 20; i++) {
            if (i % 10 == 0) {
                seq++;                                  // one lost in ten
            }
            datagram(robot, MessageType.LD2450, Ld2450.build(List.of()), i * 0.1);
        }
        monitor.tick(2.0);
        DeviceStatus s = monitor.statuses().getFirst();
        assertThat(s.rates().get("radar")).isEqualTo(10.0);
        assertThat(s.lossPct()).isEqualTo(9.1);         // 2 lost for 20 received
        assertThat(s.online()).isTrue();
        assertThat(monitor.tick(7.9)).isEmpty();
        assertThat(monitor.tick(8.1)).singleElement().isInstanceOf(DeviceMonitor.Notice.Disconnected.class);
        assertThat(monitor.anyOnline()).isFalse();
        datagram(robot, MessageType.LOG, "back".getBytes(), 9);
        List<DeviceMonitor.Notice> back = monitor.tick(9.1);
        assertThat(back).hasSize(2);
        assertThat(back.get(0)).isEqualTo(new DeviceMonitor.Notice.Log("marvin-040501", "back", 1_790_000_009.0));
        assertThat(back.get(1)).isInstanceOf(DeviceMonitor.Notice.Reconnected.class);
    }
}

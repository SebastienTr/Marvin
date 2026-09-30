// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import marvin.host.domain.robot.event.RoomState;

/** The room's latest readings and their age, as the Python host's {@code room.Room} keeps them. */
class RoomTest {
    private final Device bench = new Device(new Hello(new byte[] {8, (byte) 0xd1, (byte) 0xf9, 0x11, 0x22, 0x33},
            Board.ESP32_DEVKIT, ProtocolV1.FLAG_SIMULATED | ProtocolV1.FLAG_ENVIRONMENT, -55, 1, "0.1.0"),
            new Endpoint("192.168.1.43", 47101));
    private final Room room = new Room();

    @Test
    void keepsTheLatestReadingAndItsAge() {
        assertThat(room.state(1000)).isNull();
        room.record(bench, new Environment(true, true, false, false, false, 23.44, 48.0, null, null, 30, false), 1000);
        RoomState s = room.state(1012.34);
        assertThat(s.fresh()).isTrue();
        assertThat(s.ageS()).isCloseTo(12.34, within(1e-9));
        assertThat(s.device()).isEqualTo("marvin-112233");
        assertThat(s.motionSensor()).isTrue();
        assertThat(s.motion()).isFalse();
        assertThat(s.motionAgeS()).as("on the host's clock").isCloseTo(42.34, within(1e-9));
        assertThat(s.temperatureC()).isEqualTo(23.44);
        assertThat(s.lightWord()).isNull();
        assertThat(room.state(1120).fresh()).isFalse();

        room.record(bench, new Environment(true, false, true, true, true, null, null, 0.05, 3890, null, false), 1200);
        s = room.state(1201);
        assertThat(s.warmingUp()).isTrue();
        assertThat(s.motion()).as("nothing while warming up").isFalse();
        assertThat(s.motionAgeS()).isNull();
        assertThat(s.lightWord()).isEqualTo("dark");
        assertThat(RoomState.lightWord(0.15)).isEqualTo("dim");
        assertThat(RoomState.lightWord(0.45)).isEqualTo("bright");
    }
}

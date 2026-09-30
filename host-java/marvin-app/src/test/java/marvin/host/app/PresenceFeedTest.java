// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import marvin.host.application.presence.port.in.ObservePresence;
import marvin.host.domain.presence.TargetSighting;
import marvin.host.domain.presence.VitalsReading;
import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.Endpoint;
import marvin.host.domain.robot.Environment;
import marvin.host.domain.robot.Hello;
import marvin.host.domain.robot.Ld2450;
import marvin.host.domain.robot.ProtocolV1;
import marvin.host.domain.robot.SensorFrame;
import marvin.host.domain.robot.Vitals;

/** What reaches the brain: a bench board's real PIR stands in for its simulated radar ({@link PresenceFeed}). */
class PresenceFeedTest {
    private final List<String> calls = new ArrayList<>();
    private final PresenceFeed feed = new PresenceFeed(new ObservePresence() {
        @Override
        public void onTargets(long tUs, boolean simulated, List<TargetSighting> targets) {
            calls.add("targets " + tUs);
        }

        @Override
        public void onVitals(long tUs, boolean simulated, VitalsReading reading) {
            calls.add("vitals " + tUs);
        }

        @Override
        public void onMotion(long tUs, boolean simulated, boolean motion, boolean warmingUp) {
            calls.add("motion " + tUs + " " + motion + (warmingUp ? " warming" : "") + (simulated ? " sim" : ""));
        }
    });

    private static Device device(int flags, int port) {
        return new Device(new Hello(new byte[] {8, 9, 10, 0x11, 0x22, 0x33}, Board.ESP32_DEVKIT, flags, -50, 1, "0.1.0"),
                new Endpoint("10.0.0.9", port));
    }

    private void radar(Device d, long tUs) {
        feed.onFrame(new SensorFrame.RadarTargets(d, tUs, List.of(new Ld2450.Target(0, 1000, 0, 320)),
                List.<double[]>of(new double[] {0, -1000, 160})));
        feed.onFrame(new SensorFrame.VitalSigns(d, tUs, new Vitals(true, 14, 60, 0, 0, 900)));
    }

    private void room(Device d, long tUs, Environment e) {
        feed.onFrame(new SensorFrame.RoomReading(d, tUs, e));
    }

    private static Environment pir(boolean motion, boolean warmingUp) {
        return new Environment(true, true, false, motion, warmingUp, 23.4, 48.0, null, null, motion ? 0 : null, false);
    }

    @Test
    void aSimulatedBoardWithAPirIsThePresenceThroughItsPirOnly() {
        Device bench = device(ProtocolV1.FLAG_SIMULATED | ProtocolV1.FLAG_ENVIRONMENT, 47101);
        radar(bench, 1);                        // before its first ENVIRONMENT: the HELLO flag holds it back
        room(bench, 2, pir(false, true));
        radar(bench, 3);
        room(bench, 4, pir(true, false));
        assertThat(calls).containsExactly("motion 2 false warming", "motion 4 true");
    }

    @Test
    void aSimulatedBoardWithoutAPirKeepsItsSimulatedPerson() {
        Device climateOnly = device(ProtocolV1.FLAG_SIMULATED | ProtocolV1.FLAG_ENVIRONMENT, 47102);
        room(climateOnly, 1, new Environment(false, true, false, false, false, 21.0, 50.0, null, null, null, false));
        radar(climateOnly, 2);
        Device plain = device(ProtocolV1.FLAG_SIMULATED, 47103);
        radar(plain, 3);
        assertThat(calls).containsExactly("targets 2", "vitals 2", "targets 3", "vitals 3");
    }

    @Test
    void aRealRadarIsKeptAndItsPirGoesToTheBrainToo() {
        Device robot = device(ProtocolV1.FLAG_ENVIRONMENT, 47104);
        room(robot, 1, pir(true, false));
        radar(robot, 2);
        assertThat(calls).containsExactly("motion 1 true", "targets 2", "vitals 2");
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.robot.port.in.RobotChimes;
import marvin.host.domain.conversation.VoiceSnapshot;
import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.Button;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.Endpoint;
import marvin.host.domain.robot.Hello;
import marvin.host.domain.robot.ProtocolV1;
import marvin.host.domain.robot.SensorFrame;

/** The robot's orange knob on the voice ({@link ButtonFeed}): a tap is Talk now, a long press mutes or unmutes (C1). */
class RobotKnobTest {
    private final List<String> calls = new ArrayList<>();
    private final List<String> chimed = new ArrayList<>();
    private String state = "on";
    private String status = "idle";
    private boolean muted;

    private final RobotChimes chimes = new RobotChimes() {
        @Override
        public void chime(String sound) {
            chimed.add(sound);
        }

        @Override
        public void chime(String device, String sound) {
            chimed.add(device + ":" + sound);
        }
    };

    private final VoiceControl voice = (VoiceControl) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {VoiceControl.class}, (proxy, m, args) -> switch (m.getName()) {
                case "snapshot" -> new VoiceSnapshot(state, status, muted, "", "", "m", true, true, null, false);
                case "listenNow" -> {
                    if (!"on".equals(state)) {
                        throw new VoiceControl.VoiceOff("Marvin's voice is off");
                    }
                    calls.add("listen " + args[0]);
                    yield null;
                }
                case "mute" -> {
                    muted = (Boolean) args[0];
                    calls.add("mute " + args[0]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });

    private final ButtonFeed feed = new ButtonFeed(() -> voice, () -> chimes, Runnable::run);
    private final Device robot = new Device(new Hello(new byte[] {1, 2, 3, 0x44, 0x55, 0x66}, Board.XIAO_ESP32S3_SENSE,
            ProtocolV1.FLAG_CAMERA | ProtocolV1.FLAG_AUDIO | ProtocolV1.FLAG_BUTTONS, -50, 1, "0.1.0"),
            new Endpoint("10.0.0.7", 47101));

    private void knob(int action) {
        feed.onFrame(new SensorFrame.ButtonPress(robot, 0, Button.TALK, action));
    }

    @Test
    void tapIsTalkNowAndLongPressTogglesMute() {
        knob(Button.PRESS);
        status = "listening";
        knob(Button.PRESS);                     // a second tap closes the window
        knob(Button.LONG_PRESS);
        knob(Button.LONG_PRESS);
        assertThat(calls).containsExactly("listen true", "listen false", "mute true", "mute false");
        assertThat(chimed).as("the voice's earcons say it, not the error sound").isEmpty();
    }

    @Test
    void longPressMutesEvenWhileTheVoiceIsOff() {
        state = "off";
        knob(Button.LONG_PRESS);
        assertThat(calls).containsExactly("mute true");
        assertThat(chimed).isEmpty();
        knob(Button.PRESS);
        assertThat(chimed).containsExactly("marvin-445566:error");
    }

    @Test
    void aLongPressOnTheMuteButtonIsIgnored() {
        feed.onFrame(new SensorFrame.ButtonPress(robot, 0, Button.MUTE, Button.LONG_PRESS));
        assertThat(calls).isEmpty();
    }
}

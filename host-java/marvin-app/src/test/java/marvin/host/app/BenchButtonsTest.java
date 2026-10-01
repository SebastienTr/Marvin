// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

/** The bench board's buttons on the voice ({@link ButtonFeed}) and the voice's chimes on its buzzer ({@link VoiceChimes}). */
class BenchButtonsTest {
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

    /** The voice as the app controls it: only what the buttons use. */
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
    private final Device bench = new Device(new Hello(new byte[] {8, 9, 10, 0x11, 0x22, 0x33}, Board.ESP32_DEVKIT,
            ProtocolV1.FLAG_BUTTONS | ProtocolV1.FLAG_BUZZER, -50, 1, "0.1.0"), new Endpoint("10.0.0.9", 47101));

    private void press(int button, int action) {
        feed.onFrame(new SensorFrame.ButtonPress(bench, 0, button, action));
    }

    @Test
    void talkIsTalkNowAndMuteToggles() {
        press(Button.TALK, Button.PRESS);
        status = "listening";
        press(Button.TALK, Button.PRESS);               // again while listening: closes the window
        press(Button.MUTE, Button.PRESS);
        press(Button.MUTE, Button.PRESS);
        press(9, Button.PRESS);                         // unknown button, unknown action: ignored
        press(Button.TALK, 7);
        assertThat(calls).containsExactly("listen true", "listen false", "mute true", "mute false");
        assertThat(chimed).isEmpty();
    }

    @Test
    void talkWhileTheVoiceIsOffBeepsOnTheRobot() {
        state = "off";
        press(Button.TALK, Button.PRESS);
        assertThat(calls).isEmpty();
        assertThat(chimed).containsExactly("marvin-112233:error");
    }

    private static Map<String, Object> voiceState(String state, String status, boolean muted, boolean chime) {
        return voiceState(state, status, muted, chime, false, "buzzer");
    }

    private static Map<String, Object> voiceState(String state, String status, boolean muted, boolean chime, boolean asked,
                                                  String earcons) {
        Map<String, Object> m = new HashMap<>();
        m.put("state", state);
        m.put("status", status);
        m.put("muted", muted);
        m.put("chime", chime);
        m.put("listen_s", null);
        m.put("asked", asked);
        m.put("earcons", chime ? earcons : "none");
        return m;
    }

    private static Map<String, Object> asked(String earcons) {
        return voiceState("on", "listening", false, true, true, earcons);
    }

    @Test
    void chimesFollowTheVoice() {
        Map<String, Object> idle = voiceState("on", "idle", false, true);
        assertThat(VoiceChimes.chime(idle, asked("buzzer"))).isEqualTo("wake");
        assertThat(VoiceChimes.chime(voiceState("on", "speaking", false, true), asked("buzzer")))
                .as("Talk now while it speaks").isEqualTo("wake");
        assertThat(VoiceChimes.chime(voiceState("on", "speaking", false, true), voiceState("on", "listening", false, true)))
                .as("the follow-up window after an answer").isNull();
        assertThat(VoiceChimes.chime(voiceState("on", "listening", false, true), idle))
                .as("the follow-up window closing").isNull();
        assertThat(VoiceChimes.chime(asked("buzzer"), voiceState("on", "thinking", false, true))).isEqualTo("done");
        assertThat(VoiceChimes.chime(asked("buzzer"), idle)).isEqualTo("done");
        assertThat(VoiceChimes.chime(idle, voiceState("error", "off", false, true))).isEqualTo("error");
        assertThat(VoiceChimes.chime(idle, voiceState("on", "idle", true, true))).isEqualTo("done");
        assertThat(VoiceChimes.chime(voiceState("on", "idle", true, true), idle)).isEqualTo("wake");
        assertThat(VoiceChimes.chime(null, voiceState("on", "idle", true, true))).isNull();
        assertThat(VoiceChimes.chime(idle, voiceState("on", "listening", false, false, true, "none")))
                .as("chime setting off").isNull();

        List<String> device = new ArrayList<>();
        VoiceChimes listener = new VoiceChimes(chimes, sound -> device.add(sound));
        listener.onVoice("voice", idle);
        listener.onVoice("transcript", Map.of("kind", "heard"));
        listener.onVoice("voice", asked("buzzer"));                    // the voice's device has none: the buzzer
        listener.onVoice("voice", voiceState("on", "thinking", false, true));
        listener.onVoice("voice", voiceState("on", "speaking", false, true));
        assertThat(chimed).containsExactly("wake", "done");
        listener.onVoice("voice", voiceState("on", "speaking", false, true, false, "device"));
        listener.onVoice("voice", asked("device"));                    // the computer's speaker: never both
        assertThat(device).containsExactly("wake");
        assertThat(chimed).containsExactly("wake", "done");
        VoiceChimes refused = new VoiceChimes(chimes, sound -> false);   // the voice could not play it
        refused.onVoice("voice", idle);
        refused.onVoice("voice", asked("device"));
        assertThat(chimed).containsExactly("wake", "done", "wake");
    }
}

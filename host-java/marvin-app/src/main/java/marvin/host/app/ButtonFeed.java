// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.robot.port.in.RobotChimes;
import marvin.host.application.robot.port.out.SensorFrameListener;
import marvin.host.domain.robot.Button;
import marvin.host.domain.robot.SensorFrame;

/**
 * A robot's buttons ({@code BUTTON}, the bench board: docs/wiring-bench.md), translated into the voice's commands,
 * as the app's buttons do: Talk is Talk now (it opens a listening window, or closes the one that is open), Mute
 * mutes or unmutes the microphone. The robot's knob is a Talk button with a long press (docs/protocol.md, "Buttons"):
 * a tap is Talk now, a long press mutes or unmutes the microphone, the voice's earcons saying which. Talk while the
 * voice is off plays the error sound on that robot. The robot and conversation contexts only meet here, through their
 * ports.
 *
 * <p>The voice's commands wait a little for its new state: they run off the dispatch thread ({@code run}).
 */
public final class ButtonFeed implements SensorFrameListener {
    private static final Logger log = LoggerFactory.getLogger("marvin.host.robot");
    private final Supplier<VoiceControl> voice;
    private final Supplier<RobotChimes> chimes;
    private final Consumer<Runnable> run;

    /** {@code voice} and {@code chimes} are looked up at the first press (the voice is built after the link). */
    public ButtonFeed(Supplier<VoiceControl> voice, Supplier<RobotChimes> chimes, Consumer<Runnable> run) {
        this.voice = Objects.requireNonNull(voice, "voice");
        this.chimes = Objects.requireNonNull(chimes, "chimes");
        this.run = Objects.requireNonNull(run, "run");
    }

    /** On a virtual thread per press. */
    public ButtonFeed(Supplier<VoiceControl> voice, Supplier<RobotChimes> chimes) {
        this(voice, chimes, r -> Thread.ofVirtual().name("marvin-button").start(r));
    }

    @Override
    public void onFrame(SensorFrame frame) {
        if (!(frame instanceof SensorFrame.ButtonPress b)) {
            return;
        }
        boolean longPress = b.action() == Button.LONG_PRESS && b.button() == Button.TALK;
        if (b.action() != Button.PRESS && !longPress) {
            return;                     // an action this host does not know, or a long press on a button without one
        }
        String name = Button.name(b.button());
        if (name == null) {
            return;                     // a button this host does not know
        }
        log.info("{}: {} button{}", b.device().name(), name, longPress ? ", long press" : "");
        String device = b.device().name();
        run.accept(() -> act(device, b.button(), longPress));
    }

    private void act(String device, int button, boolean longPress) {
        VoiceControl v = voice.get();
        try {
            if (button == Button.MUTE || longPress) {
                v.mute(!v.snapshot().muted());
            } else {
                v.listenNow(!"listening".equals(v.snapshot().status()));
            }
        } catch (VoiceControl.VoiceOff e) {
            log.info("{}: the voice is off", device);
            chimes.get().chime(device, "error");
        } catch (RuntimeException e) {
            log.warn("{}: button not handled", device, e);
        }
    }
}

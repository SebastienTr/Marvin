// SPDX-License-Identifier: MIT
package marvin.host.application.system.port.in;

import java.util.List;
import java.util.Map;

/**
 * Use case: the first-run setup (the app's {@code #setup}, docs/ui.md "Setting Marvin up"; contract C2,
 * {@code golden/api/get/setup.json}). Nine steps, in this order, each {@code ok}, {@code missing} or {@code skipped}
 * with one line on what was found and, when something is missing, one line on how to fix it. Setup is done once every
 * step is ok or skipped.
 */
public interface RunSetup {

    /** The steps, in the order the app shows them. */
    List<String> STEPS = List.of("host", "model", "voice", "robot", "mr60", "speaker", "voiceprint", "owner", "quiet_hours");

    /**
     * One step.
     *
     * @param id     one of {@link #STEPS}
     * @param state  {@code ok}, {@code missing} or {@code skipped}
     * @param detail one line on what was found, for the owner
     * @param fix    a command or an action that fixes it, or {@code null}
     */
    record Step(String id, String state, String detail, String fix) {
    }

    /** Every step, and whether the setup is done (every step ok or skipped). */
    record Status(List<Step> steps, boolean done) {
    }

    /** Where the setup is now: every check runs again. */
    Status status();

    /**
     * Acts on one step, then answers the status: {@code {}} checks again, {@code {"skip": true}} skips it ({@code false}
     * takes the skip back), {@code {"play": true}} plays the speaker test, {@code {"heard": true|false}} records whether
     * it was heard, the owner's {@code {"name", "languages"}} for {@code owner}, the quiet hours'
     * {@code {"enabled", "start", "end"}} for {@code quiet_hours}.
     *
     * @throws UnknownStep              not one of {@link #STEPS}
     * @throws IllegalArgumentException a body this step does not take, or a value the settings refuse
     */
    Status act(String step, Map<String, ?> body);

    /** A step that does not exist. */
    final class UnknownStep extends RuntimeException {
        public UnknownStep(String step) {
            super("unknown setup step: " + step);
        }
    }
}

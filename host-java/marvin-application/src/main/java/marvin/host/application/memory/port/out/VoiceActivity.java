// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

/**
 * Whether the voice is in use: the memory worker gives the model (and the GPU) back to the conversation as soon
 * as it is (docs/design.md 5.2, "yields to the voice").
 */
public interface VoiceActivity {

    /** Someone is talking with Marvin now (listening, thinking or speaking, or a turn in progress). */
    boolean busy();

    /** The wall-clock time (Unix seconds) of the last sign of conversation, 0 when none since start. */
    double lastActivity();

    VoiceActivity NONE = new VoiceActivity() {
        @Override
        public boolean busy() {
            return false;
        }

        @Override
        public double lastActivity() {
            return 0;
        }
    };
}

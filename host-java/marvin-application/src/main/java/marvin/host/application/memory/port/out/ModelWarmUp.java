// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.out;

/**
 * Re-fills the voice model's prompt cache after a pass (the pass used the model, or the profile in the system
 * prompt changed), so the next question is not slow (docs/design.md 5.2).
 */
public interface ModelWarmUp {

    void rewarm();

    ModelWarmUp NONE = () -> { };
}

// SPDX-License-Identifier: MIT
package marvin.host.application.soul.port.in;

/**
 * The soul as a system prompt carries it (docs/design.md 8.1: right after the rules): the core, then the learned part.
 * It changes rarely, so the model server's prompt cache holds.
 */
public interface SoulPrompt {

    /**
     * @param key  changes exactly when the text does ({@code c<core version>.l<learned version>})
     * @param text the prompt's lines, empty when there is no soul
     */
    record Text(String key, String text) {
    }

    Text soulPrompt();
}

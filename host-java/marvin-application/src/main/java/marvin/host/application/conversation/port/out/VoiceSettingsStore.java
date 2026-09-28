// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.Map;

/**
 * The voice's settings file ({@code voice.json}, shared with the Python host's {@code marvin-host talk}).
 */
public interface VoiceSettingsStore {

    /** The settings, {@code {}} when there are none. */
    Map<String, Object> load();

    /** Merges {@code values} into the file (other keys are kept). */
    void save(Map<String, Object> values);
}

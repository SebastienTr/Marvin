// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.util.Map;

import marvin.host.domain.memory.MemorySettings;

/** The owner's memory settings: switches per source, models, the nightly hour. */
public interface ConfigureMemory {

    MemorySettings settings();

    /** Changes some settings; throws {@link MemorySettings.Invalid} with a message for the owner. */
    MemorySettings update(Map<String, ?> changes);
}

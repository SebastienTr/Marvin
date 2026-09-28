// SPDX-License-Identifier: MIT
package marvin.host.application.settings.port.out;

import java.util.Map;

/** Where the settings are kept: one JSON-like value (Boolean, Number, String, Map, List) per key. */
public interface SettingsStore {

    Map<String, Object> load();

    /** Stores these keys (others are left as they are), all or nothing. */
    void save(Map<String, Object> values);
}

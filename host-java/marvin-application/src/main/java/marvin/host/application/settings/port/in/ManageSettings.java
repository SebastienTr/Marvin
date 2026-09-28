// SPDX-License-Identifier: MIT
package marvin.host.application.settings.port.in;

import java.util.Map;

import marvin.host.domain.settings.AppSettings;

/** The app's settings: read, and change from the app. */
public interface ManageSettings {

    AppSettings current();

    AppSettings defaults();

    /**
     * Applies an update (JSON-like values), stores what changed and tells the listeners; an
     * {@link marvin.host.domain.settings.InvalidSettingException} with nothing applied if it is refused.
     */
    AppSettings update(Map<String, ?> update);
}

// SPDX-License-Identifier: MIT
package marvin.host.application.settings.port.out;

import marvin.host.domain.settings.AppSettings;

/** Hears about every change of the settings (the brain's break interval, the app). */
public interface SettingsListener {

    void onChanged(AppSettings settings);
}

// SPDX-License-Identifier: MIT
package marvin.host.application.presence.port.out;

import marvin.host.domain.presence.history.StoredEvent;

/** Hears about every event as it is stored (the app's live list and log). Keep it short. */
public interface PresenceHistoryListener {

    void onStored(StoredEvent event);
}

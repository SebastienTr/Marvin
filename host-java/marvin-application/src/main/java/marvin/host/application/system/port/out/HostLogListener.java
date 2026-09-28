// SPDX-License-Identifier: MIT
package marvin.host.application.system.port.out;

import marvin.host.domain.system.LogEntry;

/** Hears about every new line of the log (the app's live Log panel). Keep it short. */
public interface HostLogListener {

    void onEntry(LogEntry entry);
}

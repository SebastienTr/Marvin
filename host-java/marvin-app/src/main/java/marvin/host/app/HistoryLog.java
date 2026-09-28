// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Map;

import marvin.host.application.presence.port.out.PresenceHistoryListener;
import marvin.host.application.system.port.in.HostLog;
import marvin.host.domain.presence.history.HistoryKinds;
import marvin.host.domain.presence.history.StoredEvent;

/** Every stored event also goes to the Log panel: the brain's as "brain", the host's markers as "host". */
public final class HistoryLog implements PresenceHistoryListener {
    private final HostLog log;

    public HistoryLog(HostLog log) {
        this.log = log;
    }

    @Override
    public void onStored(StoredEvent e) {
        boolean system = HistoryKinds.SYSTEM.contains(e.kind());
        log.add(system ? "host" : "brain", "still_long".equals(e.kind()) ? "attention" : "info", e.text(), e.ts(),
                Map.of("kind", e.kind()));
    }
}

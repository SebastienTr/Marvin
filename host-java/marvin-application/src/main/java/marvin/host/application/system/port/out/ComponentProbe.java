// SPDX-License-Identifier: MIT
package marvin.host.application.system.port.out;

import marvin.host.domain.system.ComponentHealth;

/** Checks one component of the host (the database, a sidecar, Ollama). Must return quickly and never throw. */
public interface ComponentProbe {
    /** The component's name in the health report, e.g. {@code database}. */
    String name();

    ComponentHealth check();
}

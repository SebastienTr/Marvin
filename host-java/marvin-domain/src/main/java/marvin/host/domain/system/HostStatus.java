// SPDX-License-Identifier: MIT
package marvin.host.domain.system;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The host's health: overall status, version, mode, and each component.
 *
 * @param version    the host's version
 * @param mode       live or demo
 * @param components by name (database, ...), in a stable order
 */
public record HostStatus(String version, RunMode mode, Map<String, ComponentHealth> components) {

    public HostStatus {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(mode, "mode");
        components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }

    /** Up when no component is down (a disabled component does not make the host unhealthy). */
    public boolean healthy() {
        return components.values().stream().noneMatch(c -> c.state() == ComponentHealth.State.DOWN);
    }
}

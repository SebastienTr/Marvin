// SPDX-License-Identifier: MIT
package marvin.host.application.system;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import marvin.host.application.system.port.in.ReportHealth;
import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.domain.system.ComponentHealth;
import marvin.host.domain.system.HostStatus;
import marvin.host.domain.system.RunMode;

/** Asks every component probe and puts the answers together. */
public final class HealthService implements ReportHealth {
    private final String version;
    private final RunMode mode;
    private final List<ComponentProbe> probes;

    public HealthService(String version, RunMode mode, List<ComponentProbe> probes) {
        this.version = Objects.requireNonNull(version, "version");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.probes = List.copyOf(probes);
    }

    @Override
    public HostStatus health() {
        Map<String, ComponentHealth> out = new LinkedHashMap<>();
        for (ComponentProbe p : probes) {
            ComponentHealth h;
            try {
                h = p.check();
            } catch (RuntimeException e) {
                h = ComponentHealth.down(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            out.put(p.name(), h);
        }
        return new HostStatus(version, mode, out);
    }
}

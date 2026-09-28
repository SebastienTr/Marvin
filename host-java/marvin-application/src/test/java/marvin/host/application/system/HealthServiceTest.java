// SPDX-License-Identifier: MIT
package marvin.host.application.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.domain.system.ComponentHealth;
import marvin.host.domain.system.RunMode;

class HealthServiceTest {

    private static ComponentProbe probe(String name, ComponentHealth h) {
        return new ComponentProbe() {
            public String name() {
                return name;
            }

            public ComponentHealth check() {
                return h;
            }
        };
    }

    @Test
    void aProbeThatThrowsIsReportedDown() {
        ComponentProbe broken = new ComponentProbe() {
            public String name() {
                return "voice";
            }

            public ComponentHealth check() {
                throw new IllegalStateException("boom");
            }
        };
        var s = new HealthService("0.1", RunMode.DEMO, List.of(probe("database", ComponentHealth.up("")), broken))
                .health();
        assertThat(s.mode()).isEqualTo(RunMode.DEMO);
        assertThat(s.components().get("database").isUp()).isTrue();
        assertThat(s.components().get("voice").state()).isEqualTo(ComponentHealth.State.DOWN);
        assertThat(s.components().get("voice").detail()).contains("boom");
        assertThat(s.healthy()).isFalse();
    }
}

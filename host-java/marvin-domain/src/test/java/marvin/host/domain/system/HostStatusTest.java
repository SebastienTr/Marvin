// SPDX-License-Identifier: MIT
package marvin.host.domain.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HostStatusTest {

    @Test
    void aDisabledComponentIsHealthyADownOneIsNot() {
        Map<String, ComponentHealth> c = new LinkedHashMap<>();
        c.put("database", ComponentHealth.up("PostgreSQL 18"));
        c.put("voice", ComponentHealth.disabled("off"));
        assertThat(new HostStatus("1", RunMode.LIVE, c).healthy()).isTrue();
        c.put("ollama", ComponentHealth.down("not running"));
        HostStatus s = new HostStatus("1", RunMode.LIVE, c);
        assertThat(s.healthy()).isFalse();
        assertThat(s.components().keySet()).containsExactly("database", "voice", "ollama");
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.domain.system.ComponentHealth;
import marvin.host.domain.system.HostStatus;
import marvin.host.domain.system.RunMode;

class HealthControllerTest {

    private static MockMvc mvc(ComponentHealth database) {
        return MockMvcBuilders.standaloneSetup(new HealthController(
                () -> new HostStatus("0.1.0", RunMode.LIVE, Map.of("database", database)))).build();
    }

    @Test
    void healthyHostAnswers200() throws Exception {
        mvc(ComponentHealth.up("PostgreSQL 18")).perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.components.database.state").value("up"));
    }

    @Test
    void aComponentDownAnswers503() throws Exception {
        mvc(ComponentHealth.down("connection refused")).perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.components.database.detail").value("connection refused"));
    }
}

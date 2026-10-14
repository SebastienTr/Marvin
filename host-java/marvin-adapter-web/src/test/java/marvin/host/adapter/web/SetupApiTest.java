// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.JsonNode;

import marvin.host.application.system.port.in.RunSetup;

/** The first-run setup's endpoints (contract C2, golden/api/get/setup.json). */
class SetupApiTest {
    static final JsonNode GOLDEN = Golden.json("api/get/setup.json");
    final List<String> calls = new ArrayList<>();

    /** The golden's status, whatever is asked. */
    final RunSetup setup = new RunSetup() {
        @Override
        public Status status() {
            List<Step> steps = new ArrayList<>();
            for (JsonNode s : GOLDEN.get("body").get("steps")) {
                steps.add(new Step(s.get("id").asString(), s.get("state").asString(), s.get("detail").asString(),
                        s.get("fix").isNull() ? null : s.get("fix").asString()));
            }
            return new Status(steps, GOLDEN.get("body").get("done").asBoolean());
        }

        @Override
        public Status act(String step, Map<String, ?> body) {
            if (!STEPS.contains(step)) {
                throw new UnknownStep(step);
            }
            if (body.containsKey("age")) {
                throw new IllegalArgumentException("the owner takes name and languages");
            }
            calls.add(step + " " + body);
            return status();
        }
    };

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SetupController(setup))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).header("Host", "localhost:8765").contentType("application/json").content(body);
    }

    @Test
    void theStatusIsTheContractsShape() throws Exception {
        String body = mvc.perform(get("/api/setup").header("Host", "localhost:8765"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(Golden.JSON.readTree(body)).isEqualTo(GOLDEN.get("body"));
    }

    @Test
    void eachStepTakesItsBodyAndAnswersTheStatus() throws Exception {
        mvc.perform(json("/api/setup/mr60", "{\"skip\": true}")).andExpect(status().isOk());
        mvc.perform(json("/api/setup/owner", "{\"name\": \"Sébastien\", \"languages\": [\"fr\", \"en\"]}"))
                .andExpect(status().isOk());
        String body = mvc.perform(json("/api/setup/speaker", "{\"heard\": true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(Golden.JSON.readTree(body)).isEqualTo(GOLDEN.get("body"));
        assertThat(calls).containsExactly("mr60 {skip=true}", "owner {name=Sébastien, languages=[fr, en]}",
                "speaker {heard=true}");
    }

    @Test
    void whatTheSetupRefuses() throws Exception {
        mvc.perform(json("/api/setup/owner", "{\"age\": 33}")).andExpect(status().isBadRequest());
        mvc.perform(json("/api/setup/owner", "[1]")).andExpect(status().isBadRequest());
        mvc.perform(json("/api/setup/lidar", "{}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/setup/owner").header("Host", "localhost:8765").contentType("text/plain").content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(json("/api/setup/owner", "{}").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(json("/api/setup/owner", "{}").header("Host", "192.168.1.9:8765").with(r -> {
            r.setRemoteAddr("192.168.1.20");
            return r;
        })).andExpect(status().isUnauthorized());
        assertThat(calls).isEmpty();
        assertThat(SetupController.isStepPath("/api/setup/quiet_hours")).isTrue();
        assertThat(SetupController.isStepPath("/api/setup/")).isFalse();
    }
}

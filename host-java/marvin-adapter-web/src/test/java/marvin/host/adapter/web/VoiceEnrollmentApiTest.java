// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.in.VoiceEnrollment;
import marvin.host.domain.conversation.VoiceSettings;

/** The voice calibration's endpoints (docs/voice.md, "Your voice"). */
class VoiceEnrollmentApiTest {
    final List<String> calls = new ArrayList<>();
    boolean voiceOn = true;

    final VoiceEnrollment enrollment = new VoiceEnrollment() {
        @Override
        public Map<String, Object> state() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", "idle");
            m.put("people", List.of(Map.of("name", "Sébastien")));
            return m;
        }

        @Override
        public Map<String, Object> start(Object name) {
            if (!(name instanceof String s) || s.isBlank()) {
                throw new VoiceSettings.InvalidVoiceSettingException("name must be the person's name");
            }
            if (!voiceOn) {
                throw new VoiceControl.VoiceOff("Turn Marvin's voice on first");
            }
            calls.add("start " + s);
            return state();
        }

        @Override
        public Map<String, Object> cancel() {
            calls.add("cancel");
            return state();
        }

        @Override
        public Map<String, Object> forget(Object name) {
            calls.add("forget " + name);
            return state();
        }
    };

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new VoiceEnrollmentController(enrollment))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).header("Host", "localhost:8765").contentType("application/json").content(body);
    }

    @Test
    void startCancelForgetAndTheState() throws Exception {
        mvc.perform(get("/api/voice/enroll").header("Host", "localhost:8765"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Sébastien")));
        mvc.perform(json("/api/voice/enroll", "{\"name\": \"Aashma\"}")).andExpect(status().isOk());
        mvc.perform(json("/api/voice/enroll/cancel", "{}")).andExpect(status().isOk());
        mvc.perform(json("/api/voice/people/forget", "{\"name\": \"Aashma\"}")).andExpect(status().isOk());
        assertThat(calls).containsExactly("start Aashma", "cancel", "forget Aashma");
    }

    @Test
    void errorsSayWhy() throws Exception {
        var bad = mvc.perform(json("/api/voice/enroll", "{\"name\": \"\"}")).andExpect(status().isBadRequest()).andReturn();
        assertThat(bad.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("name must be");
        voiceOn = false;
        mvc.perform(json("/api/voice/enroll", "{\"name\": \"Ana\"}")).andExpect(status().isConflict());
        mvc.perform(json("/api/voice/enroll", "[1]")).andExpect(status().isBadRequest());
    }
}

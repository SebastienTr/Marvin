// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.connector.port.in.ConnectorControl;
import marvin.host.domain.shared.JsonText;

/** The connectors' API behind the real access filter, on stand-in connectors. */
class ConnectorApiTest {

    static final class FakeConnectors implements ConnectorControl {
        final List<String> calls = new ArrayList<>();
        boolean client = true;

        @Override
        public Map<String, Object> connectors() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("connectors", List.of(Map.of("id", "gmail", "state", "disconnected")));
            m.put("oauth_client", client);
            return m;
        }

        @Override
        public String connect(String id, String redirectUri) {
            if (!List.of("gmail", "google_calendar").contains(id)) {
                throw new IllegalArgumentException("no such connector: " + id);
            }
            if (!client) {
                throw new NotReady("Install your Google OAuth client first");
            }
            calls.add("connect " + id + " " + redirectUri);
            return "https://accounts.google.com/o/oauth2/v2/auth?state=s1";
        }

        @Override
        public String callback(String state, String code, String error) {
            if (!"s1".equals(state)) {
                throw new Failed("This sign-in is unknown or too old: connect again from Marvin's app");
            }
            calls.add("callback " + code);
            return "gmail";
        }

        @Override
        public void disconnect(String id) {
            calls.add("disconnect " + id);
        }
    }

    final FakeConnectors connectors = new FakeConnectors();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConnectorController(connectors)).setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(ResultActions r) throws Exception {
        return (Map<String, Object>) JsonText.parse(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    ResultActions postJson(String path) throws Exception {
        return mvc.perform(post(path).header("Host", "localhost:8765").contentType("application/json").content("{}"));
    }

    @Test
    void connectInTheBrowserAndDisconnect() throws Exception {
        assertThat(json(mvc.perform(get("/api/connectors").header("Host", "localhost:8765")).andExpect(status().isOk())))
                .containsEntry("oauth_client", true);
        Map<String, Object> c = json(postJson("/api/connectors/gmail/connect").andExpect(status().isOk()));
        assertThat((String) c.get("auth_url")).startsWith("https://accounts.google.com/");
        assertThat(connectors.calls.getLast()).startsWith("connect gmail http://127.0.0.1:").endsWith("/api/connectors/callback");
        String page = mvc.perform(get("/api/connectors/callback?state=s1&code=c-1").header("Host", "127.0.0.1:8765"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<h1>Connected</h1>").contains("Gmail").doesNotContain("<script");
        assertThat(connectors.calls).contains("callback c-1");
        String bad = mvc.perform(get("/api/connectors/callback?state=forged&code=x").header("Host", "127.0.0.1:8765"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(bad).contains("Not connected").contains("unknown or too old");
        postJson("/api/connectors/gmail/disconnect").andExpect(status().isOk());
        assertThat(connectors.calls.getLast()).isEqualTo("disconnect gmail");
    }

    @Test
    void refusals() throws Exception {
        postJson("/api/connectors/slack/connect").andExpect(status().isNotFound());
        connectors.client = false;
        assertThat(json(postJson("/api/connectors/gmail/connect").andExpect(status().isConflict())))
                .containsEntry("error", "Install your Google OAuth client first");
        postJson("/api/connectors/gmail/send").andExpect(status().isNotFound());
        // the callback from another device needs the key like any API
        mvc.perform(get("/api/connectors/callback?state=s1&code=c").header("Host", "192.168.1.20:8765").with(r -> {
            r.setRemoteAddr("192.168.1.30");
            return r;
        })).andExpect(status().isUnauthorized());
    }
}

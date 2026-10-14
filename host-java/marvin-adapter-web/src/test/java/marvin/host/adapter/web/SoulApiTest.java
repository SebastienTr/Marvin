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
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.soul.port.in.SoulControl;
import marvin.host.domain.shared.JsonText;
import marvin.host.domain.soul.Guardrails;
import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulProposal;
import marvin.host.domain.soul.SoulText;
import marvin.host.domain.soul.SoulVersion;

/** The soul's API behind the real access filter, on a stand-in soul. */
class SoulApiTest {

    static final class FakeSoul implements SoulControl {
        final List<String> calls = new ArrayList<>();
        String learned = "";

        @Override
        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("core", Map.of("text", SoulText.DEFAULT_CORE, "version", 1));
            m.put("learned", Map.of("text", learned, "version", learned.isEmpty() ? 0 : 1));
            m.put("proposal", null);
            return m;
        }

        @Override
        public SoulVersion setCore(String text) {
            if (text.isBlank()) {
                throw new IllegalArgumentException("the core cannot be empty");
            }
            calls.add("core " + text);
            return new SoulVersion(SoulPart.CORE, 2, text, 1, SoulVersion.OWNER, null);
        }

        @Override
        public SoulVersion setLearned(String text) {
            List<Guardrails.Check> checks = Guardrails.check(text, SoulText.DEFAULT_CORE);
            if (!Guardrails.passes(checks)) {
                throw new Refused(checks);
            }
            learned = text;
            return new SoulVersion(SoulPart.LEARNED, 1, text, 1, SoulVersion.OWNER, null);
        }

        @Override
        public SoulVersion approve(long proposal) {
            if (proposal != 4) {
                throw new NotFound("no such proposal");
            }
            throw new Conflict("this proposal is already approved");
        }

        @Override
        public SoulProposal reject(long proposal) {
            calls.add("reject " + proposal);
            return null;
        }

        @Override
        public SoulVersion revert(SoulPart part, int version) {
            calls.add("revert " + part.id() + " " + version);
            return new SoulVersion(part, 3, "x", 1, SoulVersion.OWNER, null);
        }

        @Override
        public Optional<SoulProposal> reflect() {
            calls.add("reflect");
            return Optional.empty();
        }
    }

    final FakeSoul soul = new FakeSoul();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SoulController(soul)).setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(ResultActions r) throws Exception {
        return (Map<String, Object>) JsonText.parse(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Host", "localhost:8765").contentType("application/json").content(body));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readEditAndDecide() throws Exception {
        Map<String, Object> v = json(mvc.perform(get("/api/soul").header("Host", "localhost:8765")).andExpect(status().isOk()));
        assertThat((Map<String, Object>) v.get("core")).containsEntry("version", 1L);
        Map<String, Object> after = json(postJson("/api/soul/learned", "{\"text\": \"Prefers short answers.\"}")
                .andExpect(status().isOk()));
        assertThat((Map<String, Object>) after.get("learned")).containsEntry("text", "Prefers short answers.");
        postJson("/api/soul/core", "{\"text\": \"Calm.\"}").andExpect(status().isOk());
        assertThat(json(postJson("/api/soul/core", "{\"text\": \" \"}").andExpect(status().isBadRequest())))
                .containsEntry("error", "the core cannot be empty");
        postJson("/api/soul/revert", "{\"part\": \"learned\", \"version\": 2}").andExpect(status().isOk());
        postJson("/api/soul/proposal/7/reject", "{}").andExpect(status().isOk());
        postJson("/api/soul/reflect", "{}").andExpect(status().isOk());
        assertThat(soul.calls).containsExactly("core Calm.", "revert learned 2", "reject 7", "reflect");
        postJson("/api/soul/proposal/9/approve", "{}").andExpect(status().isNotFound());
        postJson("/api/soul/proposal/4/approve", "{}").andExpect(status().isConflict());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefusalSaysWhichGuardrailFailedAndBadRequestsAreRefused() throws Exception {
        Map<String, Object> r = json(postJson("/api/soul/learned", "{\"text\": \"Spend more time with Marvin.\"}")
                .andExpect(status().isUnprocessableEntity()));
        assertThat((String) r.get("error")).startsWith("refused by the guardrails: no_dependency");
        assertThat((List<Map<String, Object>>) r.get("guardrails")).anySatisfy(g -> assertThat(g)
                .containsEntry("rule", "no_dependency").containsEntry("ok", false));
        assertThat(soul.learned).isEmpty();
        postJson("/api/soul/learned", "{\"text\": 3}").andExpect(status().isBadRequest());
        postJson("/api/soul/revert", "{\"part\": \"all\", \"version\": 2}").andExpect(status().isBadRequest());
        postJson("/api/soul/revert", "{\"part\": \"core\", \"version\": 1.5}").andExpect(status().isBadRequest());
        postJson("/api/soul/delete", "{}").andExpect(status().isNotFound());
    }
}

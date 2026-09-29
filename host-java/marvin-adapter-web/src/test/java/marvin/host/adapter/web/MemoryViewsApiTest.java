// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.memory.ForgetConfirmations;
import marvin.host.application.memory.MemoryExportService;
import marvin.host.application.memory.MemoryViewsService;
import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.in.ManageFacts;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.JsonText;

/**
 * The memory API's pictures of memory ({@code /api/memory/graph}, {@code map}, {@code timeline}, {@code flow}) on
 * memory's use cases and in-memory stores, behind the real access filter: no database or Docker needed. What
 * {@code MemoryApiIT} does for the other routes on PostgreSQL.
 */
class MemoryViewsApiTest {
    static final Instant T = Instant.parse("2026-09-28T12:00:00Z");
    final MemoryFixture m = new MemoryFixture(T, new FakeMemoryModel());
    final ConsolidateMemory.Report nightly = new ConsolidateMemory.Report(ConsolidateMemory.Pass.NIGHTLY, "partial",
            T.getEpochSecond() - 7200, 12.5, "qwen3:4b-instruct", Map.of("days", 1, "archived", 0),
            List.of(Map.of("step", "extract", "seconds", 2.0), Map.of("step", "days", "seconds", 10.0, "error", "no model")),
            "days: no model", "ollama pull qwen3:4b-instruct");
    final ConsolidateMemory worker = new ConsolidateMemory() {
        @Override
        public CompletableFuture<Report> consolidateNow(Pass pass) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Status status() {
            return new Status("waiting", null, "", 2, T.getEpochSecond() - 600, T.getEpochSecond() - 7200,
                    T.getEpochSecond() + 3600 * 15, nightly);
        }
    };
    final MemoryController controller = new MemoryController(worker, m.admin, m.admin, m.admin,
            new ForgetConfirmations(m.store.facts, m.admin, m.embeddings, m.clock, UUID::randomUUID),
            new MemoryExportService(m.store.log, m.store.facts, m.store.episodes, m.store.profiles, m.settings, m.clock),
            m.settings, new MemoryViewsService(m.store.facts, m.store.log, m.store.episodes, m.store.profiles, worker, m.days,
            m.clock), m.clock, m.days);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    static MockHttpServletRequestBuilder local(String path) {
        return get(path).header("Host", "localhost:8765");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> json(String path) throws Exception {
        String body = mvc.perform(local(path)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return (Map<String, Object>) JsonText.parse(body);
    }

    Fact sister;
    Fact health;
    Fact forgotten;

    void someMemories() {
        sister = m.admin.remember("Claire is the owner's sister.", "person:Claire", null);
        m.clock.advance(60);
        m.admin.remember("Claire lives in Lyon.", "person:Claire", null);
        m.clock.advance(60);
        m.admin.remember("Lyon is two hours away by train.", "place:Lyon", null);
        m.clock.advance(60);
        health = m.admin.remember("Claire has asthma.", "person:Claire", Sensitivity.SENSITIVE);
        m.clock.advance(60);
        forgotten = m.admin.remember("The owner hides the spare key under the blue pot.", "owner", null);
        m.clock.advance(60);
        m.admin.forgetFact(forgotten.id());
        Fact tea = m.admin.remember("The owner drinks tea.", "owner", null);
        m.clock.advance(86_400);
        m.admin.edit(tea.id(), new ManageFacts.Edit("The owner drinks green tea.", null, null, null, null));
        m.say(T.plusSeconds(10), "heard", "Ma sœur Claire vient ce week-end");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGraphHasNodesEdgesAndTheirFactsNeverAForgottenOne() throws Exception {
        someMemories();
        Map<String, Object> g = json("/api/memory/graph");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) g.get("nodes");
        assertThat(nodes).extracting(n -> n.get("id")).containsExactly("owner", "person:claire", "place:lyon");
        assertThat(nodes.get(1)).containsEntry("name", "Claire").containsEntry("type", "person").containsEntry("sensitive", true);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) g.get("edges");
        assertThat(edges).extracting(e -> e.get("from") + "-" + e.get("to"))
                .containsExactlyInAnyOrder("owner-person:claire", "person:claire-place:lyon", "owner-place:lyon");
        Map<String, Object> facts = (Map<String, Object>) g.get("facts");
        assertThat(facts).doesNotContainKey(forgotten.id().toString());
        assertThat((Map<String, Object>) facts.get(health.id().toString())).containsEntry("sensitivity", "sensitive")
                .containsEntry("node", "person:claire");
        // each fact once, in its latest wording
        assertThat(facts.values()).extracting(f -> ((Map<String, Object>) f).get("statement"))
                .contains("The owner drinks green tea.").doesNotContain("The owner drinks tea.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMapSendsTwoNumbersPerFactNeverTheVectors() throws Exception {
        json("/api/memory/map");            // nothing yet: an empty map, not an error
        assertThat((List<?>) json("/api/memory/map").get("points")).isEmpty();
        someMemories();
        String body = mvc.perform(local("/api/memory/map")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> map = (Map<String, Object>) JsonText.parse(body);
        List<Map<String, Object>> points = (List<Map<String, Object>>) map.get("points");
        assertThat(points).hasSize(5).allSatisfy(p -> {
            assertThat(p.get("x")).isInstanceOf(Number.class);
            assertThat(p.get("y")).isInstanceOf(Number.class);
            assertThat(p).doesNotContainKey("embedding");
        });
        assertThat(points).extracting(p -> p.get("id")).doesNotContain(forgotten.id().toString());
        assertThat(body.length()).isLessThan(20_000);       // 5 facts, not 5 × 1024 numbers
        assertThat((List<Object>) map.get("explained")).hasSize(2);
        assertThat((Map<String, Object>) map.get("embeddings")).containsKey("state");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTimelineTakesARangeAndShowsTheWordingsInOneLane() throws Exception {
        someMemories();
        Map<String, Object> t = json("/api/memory/timeline?range=week");
        assertThat(t).containsEntry("range", "week").containsEntry("truncated", false);
        assertThat(((Number) t.get("to")).doubleValue() - ((Number) t.get("from")).doubleValue()).isEqualTo(7 * 86_400.0);
        List<Map<String, Object>> lanes = (List<Map<String, Object>>) t.get("lanes");
        Map<String, Object> tea = lanes.stream().filter(l -> ((List<?>) l.get("bars")).size() == 2).findFirst().orElseThrow();
        List<Map<String, Object>> bars = (List<Map<String, Object>>) tea.get("bars");
        assertThat(bars.getFirst()).containsEntry("end_kind", "replaced").containsEntry("next_kind", "reworded");
        assertThat(bars.get(1)).containsEntry("end", null).containsEntry("start_kind", "reworded");
        assertThat(lanes).flatExtracting(l -> (List<Map<String, Object>>) l.get("bars"))
                .extracting(b -> ((Map<String, Object>) b.get("fact")).get("id")).doesNotContain(forgotten.id().toString());
        mvc.perform(local("/api/memory/timeline?range=decade")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("range must be week, month, year or all"));
        assertThat(json("/api/memory/timeline")).containsEntry("range", "month");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFlowCountsEachStepAndCarriesTheLastPasses() throws Exception {
        someMemories();
        Map<String, Object> f = json("/api/memory/flow");
        List<Map<String, Object>> sources = (List<Map<String, Object>>) f.get("sources");
        assertThat(sources).extracting(s -> s.get("source")).startsWith("conversation", "brain", "owner");
        assertThat(sources.getFirst()).containsEntry("events", 1L).containsEntry("waiting", 1L);
        assertThat((Map<String, Object>) f.get("facts")).containsEntry("forgotten", 1L).containsEntry("updated", 1L)
                .containsEntry("current", 5L).containsEntry("sensitive", 1L);
        assertThat((Map<String, Object>) f.get("written")).containsKey("profile_versions");
        assertThat((Map<String, Object>) f.get("worker")).containsEntry("state", "waiting");
        List<Map<String, Object>> recent = (List<Map<String, Object>>) f.get("recent");
        assertThat(recent).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("pass", "nightly").containsEntry("outcome", "partial");
            assertThat((List<?>) r.get("steps")).hasSize(2);
        });
    }

    @Test
    void thePicturesFollowTheSameAccessRulesAsTheRestOfTheApi() throws Exception {
        for (String path : List.of("/api/memory/graph", "/api/memory/map", "/api/memory/timeline", "/api/memory/flow")) {
            // another device without the key
            mvc.perform(get(path).header("Host", "192.168.1.5:8765").with(r -> {
                r.setRemoteAddr("192.168.1.20");
                return r;
            })).andExpect(status().isUnauthorized());
            // with the key
            mvc.perform(get(path).header("Host", "192.168.1.5:8765").header("Authorization", "Bearer k3y").with(r -> {
                r.setRemoteAddr("192.168.1.20");
                return r;
            })).andExpect(status().isOk());
            // this computer, but a name that is not its own (DNS rebinding)
            mvc.perform(get(path).header("Host", "evil.example:8765")).andExpect(status().isForbidden());
        }
    }
}

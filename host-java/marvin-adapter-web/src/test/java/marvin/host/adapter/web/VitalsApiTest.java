// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.JsonNode;

import marvin.host.application.vitals.port.in.VitalsQuery;
import marvin.host.domain.vitals.VitalsCurrent;
import marvin.host.domain.vitals.VitalsMinute;
import marvin.host.domain.vitals.VitalsQuality;

/** {@code GET /api/vitals} and the {@code vitals} stream event against the C3 golden (get/vitals.json, sse.json). */
class VitalsApiTest {

    static final class FakeVitals implements VitalsQuery {
        VitalsCurrent current = new VitalsCurrent(66.3, 14.0, VitalsQuality.GOOD, 0.82, 1789983000.0);
        int hours;

        @Override
        public Optional<VitalsCurrent> current() {
            return Optional.ofNullable(current);
        }

        @Override
        public List<VitalsMinute> minutes(int hours) {
            this.hours = hours;
            return List.of(new VitalsMinute(1789979400.0, null, null, 0), new VitalsMinute(1789982880.0, 66.0, 14.0, 58),
                    new VitalsMinute(1789982940.0, 67.5, 13.5, 60));
        }
    }

    final FakeVitals vitals = new FakeVitals();
    final EventHub hub = new EventHub();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new VitalsController(vitals, hub)).setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    JsonNode getJson(String path, int status) throws Exception {
        String body = mvc.perform(get(path).header("Host", "localhost:8765")).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Golden.JSON.readTree(body);
    }

    @Test
    void answersLikeTheGolden() throws Exception {
        JsonNode golden = Golden.json("api/get/vitals.json");
        JsonNode body = getJson(golden.get("request").get("path").asString(), 200);
        assertThat(body).isEqualTo(golden.get("body"));        // the golden's values, from the same data
        assertThat(vitals.hours).isEqualTo(24);
        mvc.perform(get("/api/vitals").header("Host", "localhost:8765"))
                .andExpect(header().string("cache-control", golden.get("headers").get("cache-control").asString()))
                .andExpect(header().string("content-type", golden.get("headers").get("content-type").asString()));
    }

    @Test
    void hoursAreClampedAndMustBeAnInteger() throws Exception {
        getJson("/api/vitals?hours=200", 200);
        assertThat(vitals.hours).isEqualTo(24);
        getJson("/api/vitals?hours=0", 200);
        assertThat(vitals.hours).isEqualTo(1);
        getJson("/api/vitals", 200);
        assertThat(vitals.hours).isEqualTo(24);
        assertThat(getJson("/api/vitals?hours=many", 400).get("error").asString()).isEqualTo("hours must be an integer");
    }

    @Test
    void noSensorIsANullCurrent() throws Exception {
        vitals.current = null;
        JsonNode body = getJson("/api/vitals", 200);
        assertThat(body.get("current").isNull()).isTrue();
        assertThat(body.get("minutes")).hasSize(3);
    }

    @Test
    void qualityNoneHasNoRates() throws Exception {
        vitals.current = new VitalsCurrent(null, null, VitalsQuality.NONE, null, 1789983000.0);
        JsonNode c = getJson("/api/vitals", 200).get("current");
        assertThat(c.get("quality").asString()).isEqualTo("none");
        assertThat(c.get("hr").isNull()).isTrue();
        assertThat(c.get("br").isNull()).isTrue();
        assertThat(c.get("distance_m").isNull()).isTrue();
    }

    @Test
    void theStreamEventIsTheCurrentObjectWhileSomeoneListens() throws Exception {
        VitalsController controller = new VitalsController(vitals, hub);
        controller.publish();                                  // nobody listens: nothing is built
        BlockingQueue<EventHub.Message> q = hub.subscribe();
        controller.publish();
        EventHub.Message m = q.poll();
        assertThat(m).isNotNull();
        assertThat(m.kind()).isEqualTo("vitals");
        JsonNode payload = Golden.JSON.readTree(PyJson.write(m.payload()));
        JsonNode sample = Golden.json("api/sse.json").get("streams").get("/api/stream").get("events").get("vitals")
                .get("sample");
        assertThat(payload).isEqualTo(sample);
        assertThat(keys(payload)).containsExactlyElementsOf(keys(sample));
        vitals.current = null;
        controller.publish();
        assertThat(q.poll()).isNull();                         // no sensor: no event
    }

    static List<String> keys(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.properties().iterator(); it.hasNext(); ) {
            out.add(it.next().getKey());
        }
        return out;
    }
}

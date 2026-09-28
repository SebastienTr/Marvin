// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.JsonFetcher;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.application.conversation.tools.WeatherTool;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.shared.JsonText;

/**
 * The tools against the Python host's answers ({@code golden/conversation/vectors.json}): what the model reads
 * back from each call, the errors, and the weather for canned Open-Meteo replies.
 */
class ToolVectorsTest {
    static Map<String, Object> v;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        try (InputStream in = ToolVectorsTest.class.getResourceAsStream("/marvin/contracts/golden/conversation/vectors.json")) {
            v = (Map<String, Object>) JsonText.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    static JsonFetcher fake(Object geo, String geoError, Object forecast) {
        return (url, params, timeout) -> {
            if (url.contains("geocoding")) {
                if (geoError != null) {
                    throw new JsonFetcher.Failed(geoError, false);
                }
                return geo;
            }
            return forecast;
        };
    }

    static ToolRegistry registry(String home, JsonFetcher fetch) {
        return new ToolRegistry(List.of(WeatherTool.tool(home, fetch, () -> 0.0)), true, true, () -> 0.0);
    }

    @Test
    void weatherAnswersAreThePythonOnes() {
        for (Object o : list(v.get("weather"))) {
            Map<String, Object> c = map(o);
            ToolRegistry reg = registry((String) c.get("home"), fake(c.get("geo"), (String) c.get("geo_error"), c.get("forecast")));
            ToolResult r = reg.call("get_weather", c.get("arguments"), Map.of("language", "fr"));
            assertThat(r.ok()).as("%s", c.get("arguments")).isEqualTo(c.get("ok"));
            assertThat(r.content()).as("%s", c.get("arguments")).isEqualTo(c.get("content"));
            assertThat(r.error()).isEqualTo(c.get("error"));
            assertThat(r.arguments()).isEqualTo(c.get("arguments_checked"));
        }
    }

    @Test
    void callsAndErrorsAreThePythonOnes() {
        Map<String, Object> t = map(v.get("tools"));
        for (Object o : list(t.get("calls"))) {
            Map<String, Object> c = map(o);
            Map<String, Object> w = map(list(v.get("weather")).get(0));
            ToolRegistry reg = registry("Nice", fake(w.get("geo"), null, w.get("forecast")));
            ToolResult r = reg.call((String) c.get("name"), c.get("arguments"), Map.of());
            assertThat(r.ok()).as("%s", c).isEqualTo(c.get("ok"));
            assertThat(r.content()).as("%s", c).isEqualTo(c.get("content"));
        }
        ToolRegistry offline = new ToolRegistry(List.of(WeatherTool.tool("", fake(null, null, null), () -> 0.0)), true, false, () -> 0.0);
        assertThat(offline.call("get_weather", Map.of(), Map.of()).content()).isEqualTo(t.get("offline_unknown"));
        assertThat(offline.ollamaTools()).isNull();
        ToolRegistry disabled = new ToolRegistry(List.of(WeatherTool.tool("", fake(null, null, null), () -> 0.0)), false, true, () -> 0.0);
        assertThat(disabled.call("get_weather", Map.of(), Map.of()).content()).isEqualTo(t.get("disabled_unknown"));
        Map<String, Object> w = map(list(v.get("weather")).get(0));
        Map<String, Object> record = registry("Paris", fake(w.get("geo"), null, w.get("forecast")))
                .call("get_weather", Map.of(), Map.of("language", "fr")).record();
        record.put("seconds", 0.0);
        assertThat(record).isEqualTo(t.get("record"));
        assertThat(JsonText.write(registry("", null).ollamaTools())).isEqualTo(t.get("ollama_tools_json"));
    }

    @Test
    void aForecastIsCachedTenMinutesPerPlace() {
        int[] requests = {0};
        Map<String, Object> w = map(list(v.get("weather")).get(0));
        double[] clock = {0};
        JsonFetcher counting = (url, params, timeout) -> {
            requests[0]++;
            return url.contains("geocoding") ? w.get("geo") : w.get("forecast");
        };
        ToolRegistry reg = new ToolRegistry(List.of(WeatherTool.tool("Paris", counting, () -> clock[0])), true, true, () -> clock[0]);
        reg.call("get_weather", Map.of(), Map.of());
        reg.call("get_weather", Map.of("day", "tomorrow"), Map.of());
        assertThat(requests[0]).isEqualTo(2);                   // geocoding once, forecast once
        clock[0] = 601;
        reg.call("get_weather", Map.of(), Map.of());
        assertThat(requests[0]).isEqualTo(3);                   // the forecast again, not the place
    }
}

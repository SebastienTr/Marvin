// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

import marvin.host.domain.face.Canvas;
import marvin.host.domain.face.Face;
import marvin.host.domain.face.FaceParams;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceState;
import tools.jackson.databind.JsonNode;

/**
 * The Java face draws the Python reference face's pixels (golden/face/face_vectors.json): every
 * expression at rest, the icon, and the scripted scenario of host/scripts/face_golden.py.
 */
class FaceVectorsTest {
    private static final JsonNode V = Golden.json("face/face_vectors.json");

    static String crc(byte[] rgb) {
        CRC32 c = new CRC32();
        c.update(rgb);
        return String.format("%08x", c.getValue());
    }

    @Test
    void expressionsAtRest() {
        V.get("expressions").properties().forEach(e -> {
            JsonNode p = e.getValue().get("params");
            FaceParams params = new FaceParams(p.get("width").asDouble(), p.get("height").asDouble(),
                    p.get("radius").asDouble(), p.get("spacing").asDouble(), p.get("dy").asDouble(),
                    p.get("open").asDouble(), p.get("lid_top").asDouble(), p.get("lid_tilt").asDouble(),
                    p.get("lid_bottom").asDouble(), p.get("brightness").asDouble(), p.get("warmth").asDouble());
            assertThat(params).as(e.getKey()).isEqualTo(FaceParams.expression(e.getKey()));
            Canvas c = Face.render(params, 0, 0, 0, 0, null);
            assertThat(crc(c.toRgb888())).as(e.getKey()).isEqualTo(e.getValue().get("crc32_rgb888").asString());
        });
    }

    @Test
    void icon() {
        Canvas c = Face.render(FaceParams.expression("content"), 0, 0, 0, 0, null);
        assertThat(crc(c.rows(26, 266))).isEqualTo(V.get("icon").get("crc32_rgb888").asString());
    }

    @Test
    void scriptedScenario() {
        Face face = new Face(V.get("seed").asLong());
        List<String> wrong = new ArrayList<>();
        int steps = 0;
        for (JsonNode step : V.get("steps")) {
            for (JsonNode ev : step.get("events")) {
                face.onEvent(EventKind.fromWireName(ev.asString()).orElseThrow());
            }
            PresenceState state = state(V.get("states").get(step.get("state").asString()));
            Canvas c = face.update(state, step.get("t_ms").asLong() / 1000.0);
            steps++;
            assertThat(face.expression()).as("expression at %d ms", step.get("t_ms").asLong())
                    .isEqualTo(step.get("expression").asString());
            if (!crc(c.toRgb888()).equals(step.get("crc32_rgb888").asString())) {
                wrong.add(step.get("t_ms").asString());
            }
        }
        assertThat(steps).isGreaterThan(900);
        assertThat(wrong).as("frames that differ from the Python face").isEmpty();
    }

    static PresenceState state(JsonNode s) {
        return new PresenceState(s.get("t_us").asLong(), s.get("present").asBoolean(), s.get("seated").asBoolean(),
                vec(s.get("position")), vec(s.get("head")), num(s.get("distance_m")), s.get("speed_cms").asDouble(),
                s.get("still_s").asDouble(), s.get("seated_s").asDouble(), num(s.get("breath_rate")),
                num(s.get("heart_rate")), s.get("vitals_sensor").asBoolean(), s.get("simulated").asBoolean(),
                s.get("targets").asInt());
    }

    private static double[] vec(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = n.get(i).asDouble();
        }
        return v;
    }

    private static Double num(JsonNode n) {
        return n == null || n.isNull() ? null : n.asDouble();
    }
}

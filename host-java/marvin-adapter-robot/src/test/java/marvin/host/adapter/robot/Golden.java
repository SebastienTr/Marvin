// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The golden files of marvin-contracts, from the test classpath. */
final class Golden {
    static final ObjectMapper JSON = new ObjectMapper();

    private Golden() {
    }

    static InputStream open(String path) {
        InputStream in = Golden.class.getResourceAsStream("/marvin/contracts/golden/" + path);
        assertThat(in).as(path).isNotNull();
        return in;
    }

    static JsonNode json(String path) {
        try (InputStream in = open(path)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<JsonNode> jsonl(String path) {
        List<JsonNode> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(open(path), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null;) {
                if (!line.isBlank()) {
                    out.add(JSON.readTree(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}

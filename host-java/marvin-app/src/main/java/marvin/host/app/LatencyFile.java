// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.application.conversation.port.out.LatencyLog;
import tools.jackson.databind.ObjectMapper;

/**
 * The latency lines (v1 contract C6) in {@code <data-dir>/latency.jsonl}, one JSON object per answered turn, for
 * {@code ./marvin report}; also logged ({@code marvin.latency}). The file keeps the last {@link #KEEP} lines: past
 * {@link #MAX} it is cut back.
 */
public final class LatencyFile implements LatencyLog {
    public static final String NAME = "latency.jsonl";
    static final int KEEP = 500;
    static final int MAX = 1000;
    private static final Logger log = LoggerFactory.getLogger("marvin.latency");
    private final ObjectMapper json = new ObjectMapper();
    private final Path file;
    private int lines = -1;

    public LatencyFile(Path file) {
        this.file = file;
    }

    @Override
    public synchronized void turn(Map<String, Object> line) {
        String text = json.writeValueAsString(line);
        log.info(text);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (lines < 0) {
                lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8).size() : 0;
            }
            Files.writeString(file, text + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (++lines > MAX) {
                List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                List<String> kept = all.subList(Math.max(0, all.size() - KEEP), all.size());
                Files.write(file, kept, StandardCharsets.UTF_8);
                lines = kept.size();
            }
        } catch (IOException e) {
            log.warn("could not write {}: {}", file, e.getMessage());
        }
    }
}

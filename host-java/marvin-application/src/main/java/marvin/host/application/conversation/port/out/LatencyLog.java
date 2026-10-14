// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.Map;

/**
 * Where the latency line goes (v1 contract C6, docs/voice.md "The latency line"): one JSON object per answered turn,
 * {@code ./marvin report} reads the last ones.
 */
@FunctionalInterface
public interface LatencyLog {
    LatencyLog NONE = line -> { };

    /** One answered turn's line: {@code t}, {@code lang}, then the {@code *_ms} stages (null: did not happen). */
    void turn(Map<String, Object> line);
}

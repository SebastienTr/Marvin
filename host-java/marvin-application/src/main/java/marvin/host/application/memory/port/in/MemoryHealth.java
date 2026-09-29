// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

/** How memory is doing, for the health report and the app. */
public interface MemoryHealth {

    /**
     * @param searchMode    how similar facts are found (pgvector HNSW, or an exact scan without pgvector)
     * @param embedder      {@code ready}, {@code unavailable} or {@code unknown} (not tried yet)
     * @param embedderError the last embedding error, with {@code fix} (e.g. {@code ollama pull qwen3-embedding:8b})
     */
    record Report(String searchMode, int dimensions, String embedModel, String embedder, String embedderError, String fix,
                  long events, long facts, long pending) {
    }

    Report health();
}

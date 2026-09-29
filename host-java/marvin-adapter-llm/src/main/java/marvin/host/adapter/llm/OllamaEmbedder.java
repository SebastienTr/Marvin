// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.ollama.api.OllamaApi;

import marvin.host.application.memory.port.out.Embedder;

/**
 * Embeddings through Ollama's {@code POST /api/embed}, in batches. A missing model says how to get it
 * ({@code ollama pull bge-m3}).
 */
public final class OllamaEmbedder implements Embedder {
    static final int BATCH = 32;

    private final Map<String, OllamaApi> clients = new ConcurrentHashMap<>();

    private OllamaApi api(String host) {
        return clients.computeIfAbsent(host.replaceAll("/+$", ""), h -> OllamaApi.builder().baseUrl(h).build());
    }

    @Override
    public List<float[]> embed(String host, String model, List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<String> part = texts.subList(i, Math.min(texts.size(), i + BATCH));
            OllamaApi.EmbeddingsResponse r;
            try {
                r = api(host).embed(new OllamaApi.EmbeddingsRequest(model, part, OllamaLanguageModel.KEEP_ALIVE, null, true, null));
            } catch (RuntimeException e) {
                OllamaErrors.Failure f = OllamaErrors.of(e);
                if (f.status() == 404 || f.message().contains("not found")) {
                    throw new Unavailable("Ollama has no embedding model '" + model + "'",
                            "Run `ollama pull " + model + "`.");
                }
                if (f.status() != 0) {
                    throw new Unavailable("Ollama error " + f.status() + " while embedding: "
                            + (f.message().isEmpty() ? "failed" : f.message()), "");
                }
                throw new Unavailable("cannot reach Ollama at " + host + ": " + f.message(), OllamaErrors.install(model));
            }
            if (r == null || r.embeddings() == null || r.embeddings().size() != part.size()) {
                throw new Unavailable("Ollama returned no embeddings from '" + model + "'",
                        "Check that '" + model + "' is an embedding model (bge-m3 is the default).");
            }
            out.addAll(r.embeddings());
        }
        return out;
    }
}

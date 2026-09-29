// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.util.List;
import java.util.logging.Logger;

import marvin.host.application.memory.port.out.Embedder;

/**
 * The embedder with its last known state (for the health report and the app), and a check that its vectors fit
 * the schema.
 */
public final class Embeddings {
    private static final Logger log = Logger.getLogger("marvin.memory");

    private final Embedder embedder;
    private final MemorySettingsService settings;
    private final int dimensions;
    private volatile String state = "unknown";
    private volatile String error = "";
    private volatile String fix = "";

    public Embeddings(Embedder embedder, MemorySettingsService settings, int dimensions) {
        this.embedder = embedder;
        this.settings = settings;
        this.dimensions = dimensions;
    }

    /** One vector per text; throws {@link Embedder.Unavailable} with a fix when that cannot be done. */
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        String model = settings.settings().embedModel();
        try {
            List<float[]> out = embedder.embed(settings.host(), model, texts, dimensions);
            if (out.size() != texts.size()) {
                throw new Embedder.Unavailable("the embedding model " + model + " returned " + out.size()
                        + " vectors for " + texts.size() + " texts", "");
            }
            for (float[] v : out) {
                if (v.length != dimensions) {
                    throw new Embedder.Unavailable("the embedding model " + model + " gives " + v.length
                            + " numbers per text, memory was set up for " + dimensions,
                            "Choose an embedding model with " + dimensions + " dimensions (Qwen3-Embedding models give any size up to their own), or start "
                                    + "memory on a new database with marvin.memory.embedding-dimensions=" + v.length + ".");
                }
            }
            if (!"ready".equals(state)) {
                log.info("embeddings ready: " + model);
            }
            state = "ready";
            error = "";
            fix = "";
            return out;
        } catch (Embedder.Unavailable e) {
            if (!"unavailable".equals(state) || !error.equals(e.getMessage())) {
                log.warning("embeddings unavailable: " + e.getMessage() + (e.fix().isEmpty() ? "" : ". " + e.fix()));
            }
            state = "unavailable";
            error = e.getMessage();
            fix = e.fix();
            throw e;
        }
    }

    public float[] embed(String text) {
        return embed(List.of(text)).getFirst();
    }

    /**
     * The vector of a search (a question, a recall, a forget request), as opposed to a stored fact or
     * summary. Qwen3-Embedding retrieves better when a search carries its task; stored texts never do.
     */
    public float[] embedQuery(String text) {
        return embed(queryText(settings.settings().embedModel(), text));
    }

    static String queryText(String model, String text) {
        return model.startsWith("qwen3-embedding")
                ? "Instruct: Given a question or a remark, retrieve facts about the person that help answer it\nQuery: " + text
                : text;
    }

    public String state() {
        return state;
    }

    public String error() {
        return error;
    }

    public String fix() {
        return fix;
    }

    public String model() {
        return settings.settings().embedModel();
    }

    public int dimensions() {
        return dimensions;
    }
}

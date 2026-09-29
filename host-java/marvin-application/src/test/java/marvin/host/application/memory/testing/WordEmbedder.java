// SPDX-License-Identifier: MIT
package marvin.host.application.memory.testing;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import marvin.host.application.memory.port.out.Embedder;

/**
 * Deterministic embeddings for tests: the text's content words hashed into buckets and normalised, so that texts
 * with words in common are close (the same algorithm as the adapter tests' stub Ollama).
 */
public final class WordEmbedder implements Embedder {
    public volatile int dims = 1024;
    public volatile Unavailable failure;
    public volatile int calls;
    /** The models asked for, in order. */
    public final java.util.List<String> models = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final Set<String> STOP = Set.of("the", "owner", "and", "for", "with", "that", "this", "has", "have", "are",
            "was", "his", "her", "its", "they", "their", "from", "who", "les", "des", "une", "est");

    public static float[] embedding(String text, int dims) {
        float[] v = new float[dims];
        String norm = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        for (String w : norm.split("[^a-z0-9]+")) {
            if (w.length() < 3 || STOP.contains(w)) {
                continue;
            }
            String stem = w.length() > 5 && w.endsWith("ing") ? w.substring(0, w.length() - 3)
                    : w.length() > 4 && w.endsWith("ed") ? w.substring(0, w.length() - 2)
                    : w.length() > 4 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
            int h = stem.hashCode();
            v[Math.floorMod(h, dims)] += 1f;
            v[Math.floorMod(h * 31 + 7, dims)] += 0.5f;
        }
        double n = 0;
        for (float x : v) {
            n += x * x;
        }
        if (n == 0) {
            v[0] = 1;
            return v;
        }
        float inv = (float) (1 / Math.sqrt(n));
        for (int i = 0; i < dims; i++) {
            v[i] *= inv;
        }
        return v;
    }

    @Override
    public List<float[]> embed(String host, String model, List<String> texts) {
        models.add(model);
        calls++;
        if (failure != null) {
            throw failure;
        }
        List<float[]> out = new ArrayList<>();
        for (String t : texts) {
            out.add(embedding(t, dims));
        }
        return out;
    }
}

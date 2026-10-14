// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.domain.shared.JsonText;

/**
 * Keeps the voice's model loaded in Ollama (docs/voice.md "Keeping the voice's model warm"). The voice's model is
 * pinned: its requests ask {@code keep_alive: -1} (never unloaded for idleness). Every other model (embeddings,
 * memory's night model, the model for images) keeps {@link OllamaLanguageModel#KEEP_ALIVE}. When one of those other
 * models was just used, Ollama may have unloaded the voice's to make room: at most every {@link #CHECK_EVERY_S} it
 * asks {@code GET /api/ps}, and if the voice's model is gone it calls {@link #onEvicted} (the voice warms up again,
 * when the owner is not talking).
 */
public final class OllamaKeepAlive {
    private static final Logger log = LoggerFactory.getLogger("marvin.voice.llm");
    /** Ollama's "never unload for idleness". */
    public static final String PINNED = "-1";
    static final double CHECK_EVERY_S = 30.0;

    private final Map<String, String> pinned = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCheck = new ConcurrentHashMap<>();
    private final LongSupplier nanos;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile Runnable onEvicted = () -> { };

    public OllamaKeepAlive() {
        this(System::nanoTime);
    }

    OllamaKeepAlive(LongSupplier nanos) {
        this.nanos = nanos;
    }

    private static String host(String host) {
        return host.replaceAll("/+$", "");
    }

    /** {@code model} is the voice's on {@code host}: kept loaded from now on (replaces the one pinned before). */
    public void pin(String host, String model) {
        String before = pinned.put(host(host), Objects.requireNonNull(model, "model"));
        if (!model.equals(before)) {
            log.info("keeping {} loaded in Ollama (keep_alive {})", model, PINNED);
        }
    }

    /** What the voice does when its model was unloaded (warm it up again). */
    public void onEvicted(Runnable r) {
        onEvicted = Objects.requireNonNull(r, "onEvicted");
    }

    /** The {@code keep_alive} of a request for {@code model} on {@code host}. */
    public String of(String host, String model) {
        return model != null && model.equals(pinned.get(host(host))) ? PINNED : OllamaLanguageModel.KEEP_ALIVE;
    }

    /**
     * {@code model} was just used on {@code host}: when it is not the pinned one, checks (at most every
     * {@link #CHECK_EVERY_S}) that the pinned one is still loaded. Never throws.
     */
    public void used(String host, String model) {
        String h = host(host);
        String voice = pinned.get(h);
        if (voice == null || voice.equals(model)) {
            return;
        }
        long now = nanos.getAsLong();
        Long last = lastCheck.get(h);
        if (last != null && (now - last) / 1e9 < CHECK_EVERY_S) {
            return;
        }
        lastCheck.put(h, now);
        Thread.ofVirtual().name("marvin-ollama-ps").start(() -> check(h, voice));
    }

    /** Asks Ollama what is loaded; the voice's model missing: {@link #onEvicted}. */
    void check(String host, String voice) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(host + "/api/ps"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return;
            }
            if (!loaded(r.body(), voice)) {
                log.warn("Ollama unloaded {} for another model: warming it up again", voice);
                onEvicted.run();
            }
        } catch (java.io.IOException | RuntimeException e) {
            log.debug("could not ask Ollama what is loaded: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether {@code /api/ps}'s answer lists {@code model} (or {@code model:latest}). */
    static boolean loaded(String psBody, String model) {
        if (!(JsonText.parse(psBody) instanceof Map<?, ?> m) || !(m.get("models") instanceof List<?> models)) {
            return true;                        // cannot tell: assume it is there
        }
        for (Object o : models) {
            if (o instanceof Map<?, ?> e) {
                for (String k : List.of("name", "model")) {
                    if (e.get(k) instanceof String n && (n.equals(model) || n.equals(model + ":latest"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

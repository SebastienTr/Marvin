// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import marvin.host.domain.conversation.ChatMessage;

/** The voice's model stays loaded: keep_alive -1 for it, and a warm-up again when another model pushed it out. */
class OllamaKeepAliveTest {

    @Test
    void theVoicesModelIsPinnedTheOthersKeepThirtyMinutes() throws Exception {
        OllamaKeepAlive keep = new OllamaKeepAlive();
        try (StubOllama stub = new StubOllama()) {
            OllamaLanguageModel model = new OllamaLanguageModel(keep);
            OllamaEmbedder embedder = new OllamaEmbedder(keep);
            stub.chat = r -> StubOllama.text("Oui.");
            model.streamChat(stub.url(), "qwen3:4b-instruct", List.of(ChatMessage.user("Bonjour.")), null, 30,
                    new OllamaLanguageModelTest.Collect());
            assertThat(stub.requests.getLast()).containsEntry("keep_alive", OllamaLanguageModel.KEEP_ALIVE);
            model.keepLoaded(stub.url() + "/", "qwen3:4b-instruct");
            model.streamChat(stub.url(), "qwen3:4b-instruct", List.of(ChatMessage.user("Bonjour.")), null, 30,
                    new OllamaLanguageModelTest.Collect());
            assertThat(stub.requests.getLast()).containsEntry("keep_alive", "-1m");
            embedder.embed(stub.url(), "qwen3-embedding:8b", List.of("football on Wednesdays"));
            assertThat(stub.embedRequests.getLast()).containsEntry("keep_alive", OllamaLanguageModel.KEEP_ALIVE);
        }
    }

    @Test
    void anotherModelThatPushedTheVoicesOutWarmsItUpAgainAtMostEveryThirtySeconds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] loaded = {"qwen3-embedding:8b"};
        AtomicInteger asked = new AtomicInteger();
        server.createContext("/api/ps", ex -> {
            asked.incrementAndGet();
            byte[] b = ("{\"models\": [{\"name\": \"" + loaded[0] + "\", \"model\": \"" + loaded[0] + "\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            AtomicLong now = new AtomicLong();
            OllamaKeepAlive keep = new OllamaKeepAlive(now::get);
            AtomicInteger warmed = new AtomicInteger();
            keep.onEvicted(warmed::incrementAndGet);
            keep.used(host, "qwen3-embedding:8b");
            Thread.sleep(300);
            assertThat(asked.get()).as("nothing pinned: nothing to check").isZero();
            keep.pin(host, "qwen3.8:27b");
            keep.used(host, "qwen3.8:27b");
            Thread.sleep(300);
            assertThat(asked.get()).as("the voice's own request").isZero();
            keep.used(host, "qwen3-embedding:8b");
            waitFor(() -> warmed.get() == 1);
            keep.used(host, "qwen3-embedding:8b");                   // again within 30 s: not asked
            Thread.sleep(300);
            assertThat(asked.get()).isEqualTo(1);
            now.addAndGet(31_000_000_000L);
            loaded[0] = "qwen3.8:27b";
            keep.used(host, "qwen3-embedding:8b");
            waitFor(() -> asked.get() == 2);
            Thread.sleep(200);
            assertThat(warmed.get()).as("still loaded: no warm-up").isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void psListsModelsByNameOrModelWithLatest() {
        assertThat(OllamaKeepAlive.loaded("{\"models\": [{\"name\": \"qwen3:4b-instruct:latest\"}]}", "qwen3:4b-instruct"))
                .isTrue();
        assertThat(OllamaKeepAlive.loaded("{\"models\": []}", "qwen3:4b-instruct")).isFalse();
        assertThat(OllamaKeepAlive.loaded("{}", "qwen3:4b-instruct")).as("cannot tell").isTrue();
    }

    private static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 200 && !c.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertThat(c.getAsBoolean()).isTrue();
    }
}

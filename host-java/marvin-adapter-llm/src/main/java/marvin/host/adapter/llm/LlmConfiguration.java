// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The language model, memory's model jobs and embeddings, and the tools' internet access. */
@Configuration(proxyBeanMethods = false)
public class LlmConfiguration {

    /** One keep-alive policy for every Ollama request: the voice's model stays loaded. */
    @Bean
    public OllamaKeepAlive ollamaKeepAlive() {
        return new OllamaKeepAlive();
    }

    @Bean
    public OllamaLanguageModel ollamaLanguageModel(OllamaKeepAlive keepAlive) {
        return new OllamaLanguageModel(keepAlive);
    }

    @Bean
    public OllamaEmbedder ollamaEmbedder(OllamaKeepAlive keepAlive) {
        return new OllamaEmbedder(keepAlive);
    }

    @Bean
    public OllamaMemoryModel ollamaMemoryModel(OllamaKeepAlive keepAlive) {
        return new OllamaMemoryModel(keepAlive);
    }

    @Bean
    public HttpJsonFetcher httpJsonFetcher() {
        return new HttpJsonFetcher();
    }
}

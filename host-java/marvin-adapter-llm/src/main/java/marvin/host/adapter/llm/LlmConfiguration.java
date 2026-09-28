// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The language model and the tools' internet access. */
@Configuration(proxyBeanMethods = false)
public class LlmConfiguration {

    @Bean
    public OllamaLanguageModel ollamaLanguageModel() {
        return new OllamaLanguageModel();
    }

    @Bean
    public HttpJsonFetcher httpJsonFetcher() {
        return new HttpJsonFetcher();
    }
}

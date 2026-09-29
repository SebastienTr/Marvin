// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmbeddingsQueryTest {

    @Test
    void aQwen3SearchCarriesItsTaskAndOtherModelsGetTheTextAsIs() {
        assertThat(Embeddings.queryText("qwen3-embedding:8b", "Où j'habite ?"))
                .startsWith("Instruct: ").endsWith("\nQuery: Où j'habite ?");
        assertThat(Embeddings.queryText("bge-m3", "Où j'habite ?")).isEqualTo("Où j'habite ?");
    }
}

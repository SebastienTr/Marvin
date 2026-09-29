// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import marvin.host.domain.shared.JsonText;

/** What went wrong with a request to Ollama: its HTTP status and error text, a timeout, or the network. */
final class OllamaErrors {

    /** @param status 0 when the server did not answer with an error status */
    record Failure(int status, String message, boolean timeout) {
    }

    private OllamaErrors() {
    }

    static Failure of(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException w) {
                return new Failure(w.getStatusCode().value(), errorOf(w.getResponseBodyAsString()), false);
            }
            if (t instanceof RestClientResponseException w) {
                return new Failure(w.getStatusCode().value(), errorOf(w.getResponseBodyAsString()), false);
            }
            if (t instanceof TimeoutException) {
                return new Failure(0, "timed out", true);
            }
        }
        return new Failure(0, NetErrors.reason(e), false);
    }

    static String errorOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            if (JsonText.parse(body) instanceof Map<?, ?> m && m.get("error") instanceof String s) {
                return s;
            }
        } catch (IllegalArgumentException e) {
            // not JSON
        }
        return "";
    }

    static String install(String model) {
        return "Install Ollama (https://ollama.com/download or `brew install ollama`), start it "
                + "(`ollama serve` or the Ollama app), then `ollama pull " + model + "`.";
    }
}

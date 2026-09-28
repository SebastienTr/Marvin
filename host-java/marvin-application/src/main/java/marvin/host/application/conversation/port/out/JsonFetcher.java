// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.Map;

/** The few HTTPS requests the tools make: GET a JSON document. */
public interface JsonFetcher {

    /**
     * GETs {@code url} with {@code params} as the query string and parses the JSON answer (maps, lists,
     * strings, numbers, booleans, {@code null}).
     *
     * @throws Failed when it cannot be fetched or parsed
     */
    Object get(String url, Map<String, Object> params, double timeoutS);

    /** A request that failed; {@code timedOut} when it took too long. */
    class Failed extends RuntimeException {
        private final boolean timedOut;

        public Failed(String reason, boolean timedOut) {
            super(reason);
            this.timedOut = timedOut;
        }

        public boolean timedOut() {
            return timedOut;
        }
    }
}

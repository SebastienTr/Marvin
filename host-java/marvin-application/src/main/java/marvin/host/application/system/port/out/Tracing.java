// SPDX-License-Identifier: MIT
package marvin.host.application.system.port.out;

import java.util.Map;

/**
 * Traces (docs/design.md 10.4): a span per unit of work worth following, such as one question from the end of
 * speech to the last word. The adapter decides where they go (OpenTelemetry, nowhere) and puts the span's
 * attributes in the log context of the thread that opened it.
 */
public interface Tracing {

    /** No traces. */
    Tracing NONE = (name, attributes) -> Span.NONE;

    /** Opens a span on this thread; close it on the same thread. */
    Span start(String name, Map<String, String> attributes);

    /** An open span. */
    interface Span extends AutoCloseable {

        Span NONE = new Span() {
            @Override
            public void attribute(String key, String value) {
            }

            @Override
            public void attribute(String key, double value) {
            }

            @Override
            public void error(String message) {
            }

            @Override
            public void close() {
            }
        };

        void attribute(String key, String value);

        void attribute(String key, double value);

        /** The work failed, and why. */
        void error(String message);

        @Override
        void close();
    }
}

// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Map;

import org.slf4j.MDC;

import io.micrometer.tracing.Tracer;
import marvin.host.application.system.port.out.Tracing;

/**
 * The tracing port on Micrometer Tracing (OpenTelemetry underneath): spans exported over OTLP when an endpoint
 * is configured, trace and span ids in every log line of the thread meanwhile, and the span's own attributes
 * (the utterance id...) in the log context (MDC) too.
 */
final class MicrometerTracing implements Tracing {
    private final Tracer tracer;

    MicrometerTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Tracing.Span start(String name, Map<String, String> attributes) {
        io.micrometer.tracing.Span span = tracer.nextSpan().name(name);
        attributes.forEach((k, v) -> span.tag("marvin." + k, v));
        span.start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        attributes.forEach(MDC::put);
        return new Tracing.Span() {
            @Override
            public void attribute(String key, String value) {
                span.tag(key, value);
            }

            @Override
            public void attribute(String key, double value) {
                span.tag(key, value);
            }

            @Override
            public void error(String message) {
                span.error(new IllegalStateException(message));
            }

            @Override
            public void close() {
                attributes.keySet().forEach(MDC::remove);
                scope.close();
                span.end();
            }
        };
    }
}

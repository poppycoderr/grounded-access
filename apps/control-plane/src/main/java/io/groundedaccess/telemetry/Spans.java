package io.groundedaccess.telemetry;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.function.Function;

import org.springframework.stereotype.Component;

/**
 * Starts the spans of the query pipeline. A stage can only set attributes named in {@link SpanAttribute}, and a failure is recorded as the
 * exception's class name: exception messages can quote request or response bodies, so they never reach a span.
 */
@Component
public class Spans {

    private final Tracer tracer;

    public Spans(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Spans that record nothing, for code that runs without a tracer.
     */
    public static Spans disabled() {
        return new Spans(Tracer.NOOP);
    }

    /**
     * The span a stage runs in.
     */
    public static final class Stage {

        private final Span span;

        private Stage(Span span) {
            this.span = span;
        }

        public void set(SpanAttribute attribute, String value) {
            span.tag(attribute.key(), value);
        }

        public void set(SpanAttribute attribute, long value) {
            span.tag(attribute.key(), value);
        }
    }

    public <T> T in(String name, Function<Stage, T> work) {
        Span span = tracer.nextSpan().name(name).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return work.apply(new Stage(span));
        } catch (RuntimeException e) {
            span.tag(SpanAttribute.ERROR_TYPE.key(), e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
        }
    }
}

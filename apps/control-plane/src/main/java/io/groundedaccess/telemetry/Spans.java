package io.groundedaccess.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

/**
 * Starts the spans of the query pipeline and times each stage. A stage can only set attributes named in {@link SpanAttribute}, and a failure
 * is recorded as the exception's class name: exception messages can quote request or response bodies, so they never reach a span.
 *
 * <p>
 * The metrics carry only values from fixed sets: {@code ga.stage} is tagged with the stage name and whether it failed, {@code ga.degraded}
 * with the reason, {@code ga.answers} with the answer status.
 */
@Component
public class Spans {

    private final Tracer tracer;

    private final MeterRegistry meters;

    public Spans(Tracer tracer, MeterRegistry meters) {
        this.tracer = tracer;
        this.meters = meters;
    }

    /**
     * Spans that record nothing, for code that runs without a tracer.
     */
    public static Spans disabled() {
        return new Spans(Tracer.NOOP, new SimpleMeterRegistry());
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
        Timer.Sample sample = Timer.start(meters);
        String outcome = "ok";
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return work.apply(new Stage(span));
        } catch (RuntimeException e) {
            outcome = "error";
            span.tag(SpanAttribute.ERROR_TYPE.key(), e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
            sample.stop(Timer.builder("ga.stage").tag("stage", name).tag("outcome", outcome).publishPercentileHistogram().register(meters));
        }
    }

    /**
     * Registers the counters at zero. A counter that first appears with a value already above zero shows no increase in a rate query, so the
     * first degraded result after a start would be invisible on a dashboard.
     */
    public void expect(List<String> degradedReasons, List<String> answerStatuses) {
        degradedReasons.forEach(reason -> meters.counter("ga.degraded", "reason", reason));
        answerStatuses.forEach(status -> meters.counter("ga.answers", "status", status));
    }

    public void degraded(String reason) {
        meters.counter("ga.degraded", "reason", reason).increment();
    }

    public void answered(String status) {
        meters.counter("ga.answers", "status", status).increment();
    }
}

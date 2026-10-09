package io.groundedaccess.telemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;

/**
 * The last step before a span leaves the process. It keeps the attributes named in {@link SpanAttribute} and removes everything else an
 * instrumentation library may have attached: other attributes, all events (a recorded exception is an event that carries its message and
 * stack trace) and the status description (which is the exception message). Timing, names, ids and the status code pass through.
 */
public final class RedactingSpanExporter implements SpanExporter {

    private final SpanExporter delegate;

    public RedactingSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return delegate.export(spans.stream().<SpanData>map(Redacted::new).toList());
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private static final class Redacted extends DelegatingSpanData {

        private final Attributes attributes;

        private Redacted(SpanData span) {
            super(span);
            AttributesBuilder kept = Attributes.builder();
            span.getAttributes().forEach((key, value) -> {
                if (SpanAttribute.isAllowed(key.getKey())) {
                    put(kept, key, value);
                }
            });
            this.attributes = kept.build();
        }

        @SuppressWarnings("unchecked")
        private static <T> void put(AttributesBuilder builder, io.opentelemetry.api.common.AttributeKey<T> key, Object value) {
            builder.put(key, (T) value);
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        public int getTotalAttributeCount() {
            return attributes.size();
        }

        @Override
        public List<EventData> getEvents() {
            return List.of();
        }

        @Override
        public int getTotalRecordedEvents() {
            return 0;
        }

        @Override
        public StatusData getStatus() {
            return StatusData.create(super.getStatus().getStatusCode(), "");
        }
    }
}

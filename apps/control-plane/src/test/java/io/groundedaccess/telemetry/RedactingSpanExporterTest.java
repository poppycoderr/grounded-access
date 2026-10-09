package io.groundedaccess.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;

class RedactingSpanExporterTest {

    private final InMemorySpanExporter exported = InMemorySpanExporter.create();

    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new RedactingSpanExporter(exported)))
            .build();

    @Test
    void keepsOnlyTheAttributesOnTheAllowList() {
        Span span = provider.get("test").spanBuilder("retrieval.search").startSpan();
        span.setAttribute(SpanAttribute.RETRIEVAL_K.key(), 10L);
        span.setAttribute(SpanAttribute.POLICY_VERSION.key(), "abac/1");
        span.setAttribute("uri", "/api/v1/documents/{key}");
        span.setAttribute("http.url", "/api/v1/documents/hr-compensation-bands");
        span.setAttribute("query.text", "how much do staff engineers earn");
        span.end();

        SpanData data = exported.getFinishedSpanItems().getFirst();

        assertThat(data.getAttributes().asMap()).containsOnlyKeys(AttributeKey.longKey("retrieval.k"), AttributeKey.stringKey("policy.version"),
                AttributeKey.stringKey("uri"));
        assertThat(data.getAttributes().get(AttributeKey.longKey("retrieval.k"))).isEqualTo(10L);
        assertThat(data.getTotalAttributeCount()).isEqualTo(3);
    }

    @Test
    void removesRecordedExceptionsAndTheStatusDescriptionButKeepsTheStatusCode() {
        Span span = provider.get("test").spanBuilder("model.rerank").startSpan();
        span.recordException(new IllegalStateException("422: the passage 'salary bands are confidential' is too long"));
        span.addEvent("prompt: answer from the evidence");
        span.setStatus(StatusCode.ERROR, "422: the passage 'salary bands are confidential' is too long");
        span.end();

        SpanData data = exported.getFinishedSpanItems().getFirst();

        assertThat(data.getEvents()).isEmpty();
        assertThat(data.getTotalRecordedEvents()).isZero();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getStatus().getDescription()).isEmpty();
        assertThat(data.getName()).isEqualTo("model.rerank");
        assertThat(data.toString()).doesNotContain("salary").doesNotContain("prompt:");
    }
}

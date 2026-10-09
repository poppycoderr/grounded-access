package io.groundedaccess.api;

import io.groundedaccess.telemetry.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace id before authentication runs, so rejected requests carry one too. The id is the one of the request's span,
 * so the audit rows, the execution record, the log lines and the trace of a request share it. It is returned in {@code X-Trace-Id} and put
 * into the logging context; it is the only request-derived value that enters logs.
 *
 * <p>
 * The filter runs directly after the one that starts the request's span. Without a span (tracing switched off) the id is read from the
 * {@code traceparent} header or generated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    private final Tracer tracer;

    public TraceFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Span span = tracer.currentSpan();
        String traceId = span != null ? span.context().traceId() : TraceContext.fromTraceparent(request.getHeader("traceparent"));
        TraceContext.set(traceId);
        MDC.put("traceId", traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            TraceContext.clear();
        }
    }
}

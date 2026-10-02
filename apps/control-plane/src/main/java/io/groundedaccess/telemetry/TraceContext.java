package io.groundedaccess.telemetry;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The trace id of the request being handled on this thread. It is taken from a W3C {@code traceparent} header when the caller sends a valid
 * one, and generated otherwise, so every audit row, execution record and log line of one request can be found from a single id.
 */
public final class TraceContext {

    private static final Pattern TRACEPARENT = Pattern.compile("[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");

    private static final String INVALID_TRACE_ID = "0".repeat(32);

    private static final ThreadLocal<@Nullable String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * The trace id of a {@code traceparent} header, or a new one if the header is missing or malformed. A caller cannot inject arbitrary text
     * into logs or audit rows through this header: anything that is not 32 lowercase hex digits is discarded.
     */
    public static String fromTraceparent(@Nullable String header) {
        if (header != null) {
            Matcher matcher = TRACEPARENT.matcher(header.strip());
            if (matcher.matches() && !INVALID_TRACE_ID.equals(matcher.group(1))) {
                return matcher.group(1);
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void set(String traceId) {
        CURRENT.set(traceId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * The current trace id. Work that does not start from an HTTP request, such as a background job, gets a fresh id.
     */
    public static String current() {
        String traceId = CURRENT.get();
        return traceId != null ? traceId : fromTraceparent(null);
    }
}

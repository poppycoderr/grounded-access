package io.groundedaccess.telemetry;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every attribute a span may carry. The first group is set by this application through {@link Spans}; the second is set by the HTTP
 * instrumentation and kept because it holds no request content. {@link RedactingSpanExporter} drops any other attribute before export, so
 * adding an attribute means adding it here, where it can be reviewed.
 */
public enum SpanAttribute {

    PIPELINE_CONFIG_HASH("pipeline.config_hash"),

    POLICY_VERSION("policy.version"),

    RETRIEVAL_STRATEGY("retrieval.strategy"),

    RETRIEVAL_K("retrieval.k"),

    /** Rows a channel returned. The authorization predicate is part of the channel's SQL, so this never counts a row it removed. */
    RETRIEVAL_CANDIDATES("retrieval.candidates"),

    RETRIEVAL_RESULTS("retrieval.results"),

    MODEL_NAME("model.name"),

    DEGRADED("degraded"),

    ERROR_TYPE("error.type"),

    ANSWER_STATUS("answer.status"),

    ANSWER_STATEMENTS("answer.statements"),

    ANSWER_REJECTED_STATEMENTS("answer.rejected_statements"),

    CONTEXT_EVIDENCE("context.evidence"),

    PROMPT_VERSION("prompt.version");

    /** The request method, the route template, the response status and the exception class: never a path value, a query string or a message. */
    private static final Set<String> INSTRUMENTATION = Set.of("method", "uri", "status", "outcome", "exception", "client.name");

    private static final Set<String> ALLOWED = Stream.concat(Arrays.stream(values()).map(SpanAttribute::key), INSTRUMENTATION.stream())
            .collect(Collectors.toUnmodifiableSet());

    private final String key;

    SpanAttribute(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static boolean isAllowed(String key) {
        return ALLOWED.contains(key);
    }
}

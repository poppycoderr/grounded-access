package io.groundedaccess.modelclient;

import org.springframework.web.client.RestClientException;

/**
 * The model service refused work it could not start in time (ADR-0010): its lane was full ({@code BUSY}) or the caller's deadline passed
 * while the request waited ({@code DEADLINE_EXCEEDED}). Nothing was computed; the same request may succeed later.
 */
public class ModelBusyException extends RestClientException {

    private final String reason;

    public ModelBusyException(String reason) {
        super("the model service refused the request: " + reason);
        this.reason = reason;
    }

    /**
     * {@code BUSY} or {@code DEADLINE_EXCEEDED}; a bounded set, so it can tag a metric.
     */
    public String reason() {
        return reason;
    }
}

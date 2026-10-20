package io.groundedaccess.modelclient;

import org.springframework.web.client.RestClientException;

/**
 * The model service refused a text because it is longer than the model accepts (ADR-0009). For a query that is the caller's error; for a
 * chunk it means the chunk budget and the model disagree. The message names counts and limits, never the text.
 */
public class TextTooLongException extends RestClientException {

    public TextTooLongException(String message) {
        super(message);
    }
}

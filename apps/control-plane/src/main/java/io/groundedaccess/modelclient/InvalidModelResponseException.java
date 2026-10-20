package io.groundedaccess.modelclient;

import org.springframework.web.client.RestClientException;

/**
 * The model service answered, but not with what the contract promises: another model, an unpinned revision, a wrong number of vectors or
 * scores, or values that are not finite. Such a response is never used. The message names the defect and never contains the vectors or text.
 */
public class InvalidModelResponseException extends RestClientException {

    public InvalidModelResponseException(String message) {
        super(message);
    }
}

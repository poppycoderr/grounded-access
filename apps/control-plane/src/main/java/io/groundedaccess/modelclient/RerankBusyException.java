package io.groundedaccess.modelclient;

import org.springframework.web.client.RestClientException;

/**
 * Every reranking slot stayed taken for the whole rerank timeout, so the call was not sent.
 */
public class RerankBusyException extends RestClientException {

    public RerankBusyException() {
        super("no reranking slot became free within the rerank timeout");
    }
}

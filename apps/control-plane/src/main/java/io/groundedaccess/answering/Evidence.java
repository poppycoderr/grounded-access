package io.groundedaccess.answering;

import io.groundedaccess.retrieval.RetrievedChunk;

/**
 * One passage shown to the chat model, under the id the model must cite it by. An evidence item is always a chunk that retrieval returned for
 * this principal, so citing it can never reveal anything the principal could not have read.
 */
public record Evidence(
        String id,

        RetrievedChunk chunk) {
}

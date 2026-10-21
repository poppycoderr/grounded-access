package io.groundedaccess.retrieval;

/**
 * A retrieval statement did not finish within {@code ga.retrieval.sql-timeout} and PostgreSQL cancelled it (review 2026-10-21, D3). The
 * request fails: a database that cannot answer is not a degraded model channel, and no partial result is returned.
 */
public class RetrievalTimeoutException extends RuntimeException {

    public RetrievalTimeoutException(Throwable cause) {
        super("a retrieval statement exceeded its time limit", cause);
    }
}

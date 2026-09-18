package io.groundedaccess.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Retrieval configurations compared by the evaluation; names match docs/evaluation/strategy.md.
 */
public enum RetrievalStrategy {
    @JsonProperty("sparse-only")
    SPARSE_ONLY,
    @JsonProperty("dense-only")
    DENSE_ONLY
}

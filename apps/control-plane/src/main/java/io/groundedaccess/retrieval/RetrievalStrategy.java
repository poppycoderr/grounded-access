package io.groundedaccess.retrieval;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Retrieval configurations compared by the evaluation; names match docs/evaluation/strategy.md.
 */
public enum RetrievalStrategy {
    SPARSE_ONLY("sparse-only", true, false),
    DENSE_ONLY("dense-only", false, true),
    HYBRID_RRF("hybrid-rrf", true, true);

    private final String wireName;

    private final boolean sparse;

    private final boolean dense;

    RetrievalStrategy(String wireName, boolean sparse, boolean dense) {
        this.wireName = wireName;
        this.sparse = sparse;
        this.dense = dense;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    boolean usesSparse() {
        return sparse;
    }

    boolean usesDense() {
        return dense;
    }
}

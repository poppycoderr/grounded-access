package io.groundedaccess.retrieval;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Retrieval configurations compared by the evaluation; names match docs/evaluation/strategy.md.
 */
public enum RetrievalStrategy {
    SPARSE_ONLY("sparse-only", true, false, false),
    DENSE_ONLY("dense-only", false, true, false),
    HYBRID_RRF("hybrid-rrf", true, true, false),
    HYBRID_RRF_RERANK("hybrid-rrf-rerank", true, true, true);

    private final String wireName;

    private final boolean sparse;

    private final boolean dense;

    private final boolean rerank;

    RetrievalStrategy(String wireName, boolean sparse, boolean dense, boolean rerank) {
        this.wireName = wireName;
        this.sparse = sparse;
        this.dense = dense;
        this.rerank = rerank;
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

    boolean usesRerank() {
        return rerank;
    }
}

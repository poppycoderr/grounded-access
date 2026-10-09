package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetrievalPlanTest {

    private static final RetrievalProperties DEFAULTS = new RetrievalProperties(50, 60, 20);

    @Test
    void theHashIsTakenOverAFixedSerializationAndNeverChangesForTheSamePlan() {
        RetrievalPlan plan = RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, DEFAULTS, "reranker");

        assertThat(plan.canonical()).isEqualTo("{\"strategy\":\"hybrid-rrf\",\"k\":10,\"candidates\":50,\"rrfK\":60,\"dedupeOverlaps\":true}");
        assertThat(plan.hash()).isEqualTo("bd71e8ef3c45267b");
    }

    @Test
    void anyDifferenceInThePlanChangesTheHash() {
        RetrievalPlan plan = RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, DEFAULTS, "reranker");

        assertThat(plan.hash()).isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 5, DEFAULTS, "reranker").hash())
                .isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, new RetrievalProperties(50, 10, 20), "reranker").hash())
                .isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, new RetrievalProperties(100, 60, 20), "reranker").hash())
                .isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.SPARSE_ONLY, 10, DEFAULTS, "reranker").hash());
    }

    @Test
    void singleChannelPlansCarryNoFusionConstantAndFetchAtLeastKCandidates() {
        RetrievalPlan plan = RetrievalPlan.of(RetrievalStrategy.DENSE_ONLY, 80, DEFAULTS, "reranker");

        assertThat(plan.rrfK()).isNull();
        assertThat(plan.candidates()).isEqualTo(80);
        assertThat(plan.canonical()).contains("\"rrfK\":null");
    }

    @Test
    void aRerankingPlanNamesItsCandidateCountAndModelAndPlansWithoutRerankingKeepTheirSerialization() {
        RetrievalPlan reranking = RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF_RERANK, 10, DEFAULTS, "Xenova/ms-marco-MiniLM-L-12-v2");

        assertThat(reranking.canonical()).isEqualTo("{\"strategy\":\"hybrid-rrf-rerank\",\"k\":10,\"candidates\":50,\"rrfK\":60,\"dedupeOverlaps\":true,"
                + "\"rerankCandidates\":20,\"reranker\":\"Xenova/ms-marco-MiniLM-L-12-v2\"}");
        assertThat(reranking.hash()).isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF_RERANK, 10, DEFAULTS, "another-model").hash())
                .isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF_RERANK, 10, new RetrievalProperties(50, 60, 30), "Xenova/ms-marco-MiniLM-L-12-v2").hash());
        assertThat(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, DEFAULTS, "any").canonical()).doesNotContain("rerank");
        assertThat(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF_RERANK, 40, DEFAULTS, "m").rerankCandidates()).as("never fewer candidates than k").isEqualTo(40);
    }
}

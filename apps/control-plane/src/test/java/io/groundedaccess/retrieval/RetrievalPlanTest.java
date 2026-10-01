package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetrievalPlanTest {

    private static final RetrievalProperties DEFAULTS = new RetrievalProperties(50, 60);

    @Test
    void theHashIsTakenOverAFixedSerializationAndNeverChangesForTheSamePlan() {
        RetrievalPlan plan = RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, DEFAULTS);

        assertThat(plan.canonical()).isEqualTo("{\"strategy\":\"hybrid-rrf\",\"k\":10,\"candidates\":50,\"rrfK\":60,\"dedupeOverlaps\":true}");
        assertThat(plan.hash()).isEqualTo("bd71e8ef3c45267b");
    }

    @Test
    void anyDifferenceInThePlanChangesTheHash() {
        RetrievalPlan plan = RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, DEFAULTS);

        assertThat(plan.hash()).isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 5, DEFAULTS).hash())
                .isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, new RetrievalProperties(50, 10)).hash())
                .isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.HYBRID_RRF, 10, new RetrievalProperties(100, 60)).hash())
                .isNotEqualTo(RetrievalPlan.of(RetrievalStrategy.SPARSE_ONLY, 10, DEFAULTS).hash());
    }

    @Test
    void singleChannelPlansCarryNoFusionConstantAndFetchAtLeastKCandidates() {
        RetrievalPlan plan = RetrievalPlan.of(RetrievalStrategy.DENSE_ONLY, 80, DEFAULTS);

        assertThat(plan.rrfK()).isNull();
        assertThat(plan.candidates()).isEqualTo(80);
        assertThat(plan.canonical()).contains("\"rrfK\":null");
    }
}

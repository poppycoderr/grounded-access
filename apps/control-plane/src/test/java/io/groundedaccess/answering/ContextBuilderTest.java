package io.groundedaccess.answering;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedaccess.retrieval.RetrievedChunk;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ContextBuilderTest {

    @Test
    void namesEvidenceInRankOrderAndStopsAtTheWordBudget() {
        List<Evidence> evidence = new ContextBuilder(7).build(List.of(chunk("a", "one two three"), chunk("b", "four five six"), chunk("c", "seven eight nine")));

        assertThat(evidence).extracting(Evidence::id).containsExactly("S1", "S2");
        assertThat(evidence).extracting(e -> e.chunk().documentKey()).containsExactly("a", "b");
    }

    @Test
    void alwaysKeepsTheFirstChunkEvenIfItAloneExceedsTheBudget() {
        assertThat(new ContextBuilder(2).build(List.of(chunk("a", "one two three four"), chunk("b", "five")))).extracting(Evidence::id).containsExactly("S1");
    }

    @Test
    void noChunksMeansNoEvidence() {
        assertThat(new ContextBuilder(100).build(List.of())).isEmpty();
    }

    private static RetrievedChunk chunk(String key, String content) {
        return new RetrievedChunk(UUID.randomUUID(), key, 1, key, "", 0, content.length(), content, "markdown/2", 1, 1.0, null, null, null, null, null, null);
    }
}

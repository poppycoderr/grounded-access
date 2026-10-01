package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RankFusionTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void aChunkBothChannelsAgreeOnOutranksOneThatOnlyOneChannelRanksFirst() {
        List<RetrievedChunk> sparse = List.of(sparse(A, "a", 1), sparse(B, "b", 2));
        List<RetrievedChunk> dense = List.of(dense(C, "c", 1), dense(B, "b", 2));

        List<RetrievedChunk> fused = RankFusion.reciprocalRank(sparse, dense, 60);

        assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly(B, A, C);
        assertThat(fused.getFirst().score()).isCloseTo(2.0 / 62, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 61, within(1e-12));
        assertThat(fused.getFirst().sparseRank()).isEqualTo(2);
        assertThat(fused.getFirst().denseRank()).isEqualTo(2);
        assertThat(fused.get(1).denseRank()).isNull();
        assertThat(fused.get(2).sparseScore()).isNull();
    }

    @Test
    void equalFusedScoresAreOrderedByDocumentKeyNotByInputOrder() {
        List<RetrievedChunk> fused = RankFusion.reciprocalRank(List.of(sparse(B, "b", 1)), List.of(dense(A, "a", 1)), 60);

        assertThat(fused).extracting(RetrievedChunk::documentKey).containsExactly("a", "b");
    }

    @Test
    void topNumbersTheKeptChunksFromOneAndStopsAtK() {
        List<RetrievedChunk> top = RankFusion.top(List.of(sparse(A, "a", 4), sparse(B, "b", 7), sparse(C, "c", 9)), 2, true);

        assertThat(top).extracting(RetrievedChunk::chunkId).containsExactly(A, B);
        assertThat(top).extracting(RetrievedChunk::rank).containsExactly(1, 2);
        assertThat(top).extracting(RetrievedChunk::sparseRank).containsExactly(4, 7);
    }

    @Test
    void dropsAChunkThatOverlapsAHigherRankedChunkOfTheSameDocumentVersion() {
        RetrievedChunk first = chunk(A, "policy", 1, 0, 100, 1);
        RetrievedChunk overlapping = chunk(B, "policy", 1, 80, 180, 2);
        RetrievedChunk adjacent = chunk(C, "policy", 1, 100, 200, 3);
        RetrievedChunk otherVersion = chunk(UUID.randomUUID(), "policy", 2, 80, 180, 4);
        RetrievedChunk otherDocument = chunk(UUID.randomUUID(), "handbook", 1, 80, 180, 5);
        List<RetrievedChunk> ordered = List.of(first, overlapping, adjacent, otherVersion, otherDocument);

        assertThat(RankFusion.top(ordered, 10, true)).extracting(RetrievedChunk::chunkId)
                .containsExactly(A, C, otherVersion.chunkId(), otherDocument.chunkId());
        assertThat(RankFusion.top(ordered, 10, false)).hasSize(5);
    }

    private static RetrievedChunk sparse(UUID id, String key, int rank) {
        return new RetrievedChunk(id, key, 1, key, "", 0, 10, key, "markdown/2", rank, 0.5, rank, 0.5, null, null);
    }

    private static RetrievedChunk dense(UUID id, String key, int rank) {
        return new RetrievedChunk(id, key, 1, key, "", 0, 10, key, "markdown/2", rank, 0.9, null, null, rank, 0.9);
    }

    private static RetrievedChunk chunk(UUID id, String key, int version, int start, int end, int rank) {
        return new RetrievedChunk(id, key, version, key, "", start, end, key, "markdown/2", rank, 1.0 / rank, rank, 1.0 / rank, null, null);
    }
}

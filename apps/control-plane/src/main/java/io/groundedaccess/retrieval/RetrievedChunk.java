package io.groundedaccess.retrieval;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A chunk the principal is authorized to see, with its final position and how each channel ranked it. A channel's rank and score are null when
 * that channel did not return the chunk among its candidates. {@code score} is the channel score for single-channel strategies and the fused
 * score for hybrid ones.
 */
public record RetrievedChunk(
        UUID chunkId,

        String documentKey,

        int versionNo,

        String title,

        String sectionPath,

        int charStart,

        int charEnd,

        String content,

        String chunkerVersion,

        int rank,

        double score,

        @Nullable Integer sparseRank,

        @Nullable Double sparseScore,

        @Nullable Integer denseRank,

        @Nullable Double denseScore) {

    RetrievedChunk ranked(int newRank, double newScore) {
        return new RetrievedChunk(chunkId, documentKey, versionNo, title, sectionPath, charStart, charEnd, content, chunkerVersion, newRank, newScore,
                sparseRank, sparseScore, denseRank, denseScore);
    }

    /**
     * The same chunk as seen by both channels: the non-null channel fields of each side are kept.
     */
    RetrievedChunk mergedWith(RetrievedChunk other) {
        return new RetrievedChunk(chunkId, documentKey, versionNo, title, sectionPath, charStart, charEnd, content, chunkerVersion, rank, score,
                sparseRank != null ? sparseRank : other.sparseRank, sparseScore != null ? sparseScore : other.sparseScore,
                denseRank != null ? denseRank : other.denseRank, denseScore != null ? denseScore : other.denseScore);
    }

    boolean overlaps(RetrievedChunk other) {
        return documentKey.equals(other.documentKey) && versionNo == other.versionNo && charStart < other.charEnd && other.charStart < charEnd;
    }
}

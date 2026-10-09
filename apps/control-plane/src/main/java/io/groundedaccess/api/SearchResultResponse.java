package io.groundedaccess.api;

import io.groundedaccess.retrieval.RetrievedChunk;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One ranked chunk. Scores and per-channel ranks are debug fields and are only returned to tokens with the {@code debug} scope.
 */
public record SearchResultResponse(
        UUID chunkId,

        String documentKey,

        int versionNo,

        String title,

        String sectionPath,

        int charStart,

        int charEnd,

        String text,

        String chunkerVersion,

        int rank,

        @Nullable Double score,

        @Nullable Integer sparseRank,

        @Nullable Double sparseScore,

        @Nullable Integer denseRank,

        @Nullable Double denseScore,

        @Nullable Integer fusedRank,

        @Nullable Double rerankScore) {

    static SearchResultResponse from(RetrievedChunk chunk, boolean debug) {
        return new SearchResultResponse(chunk.chunkId(), chunk.documentKey(), chunk.versionNo(), chunk.title(), chunk.sectionPath(), chunk.charStart(),
                chunk.charEnd(), chunk.content(), chunk.chunkerVersion(), chunk.rank(), debug ? chunk.score() : null, debug ? chunk.sparseRank() : null,
                debug ? chunk.sparseScore() : null, debug ? chunk.denseRank() : null, debug ? chunk.denseScore() : null,
                debug ? chunk.fusedRank() : null, debug ? chunk.rerankScore() : null);
    }
}

package io.groundedaccess.api;

import io.groundedaccess.retrieval.RetrievalChannel;
import io.groundedaccess.retrieval.RetrievedChunk;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One ranked chunk. The raw score is a debug field and is only returned to tokens with the {@code debug} scope.
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

        RetrievalChannel channel,

        int rank,

        @Nullable Double score) {

    static SearchResultResponse from(RetrievedChunk chunk, boolean debug) {
        return new SearchResultResponse(chunk.chunkId(), chunk.documentKey(), chunk.versionNo(), chunk.title(), chunk.sectionPath(), chunk.charStart(),
                chunk.charEnd(), chunk.content(), chunk.channel(), chunk.rank(), debug ? chunk.score() : null);
    }
}

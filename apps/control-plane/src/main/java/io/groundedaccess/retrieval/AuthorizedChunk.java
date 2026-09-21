package io.groundedaccess.retrieval;

import java.util.UUID;

/**
 * A chunk the principal is authorized to see, listed without any ranking.
 */
public record AuthorizedChunk(
        UUID chunkId,

        String documentKey,

        int versionNo,

        String title,

        String sectionPath,

        int ordinal,

        int charStart,

        int charEnd,

        String content) {

    public ChunkCursor cursor() {
        return new ChunkCursor(documentKey, versionNo, ordinal);
    }
}

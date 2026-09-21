package io.groundedaccess.api;

import io.groundedaccess.retrieval.AuthorizedChunk;

import java.util.UUID;

/**
 * One authorized chunk in a listing page.
 */
public record ChunkResponse(
        UUID chunkId,

        String documentKey,

        int versionNo,

        String title,

        String sectionPath,

        int charStart,

        int charEnd,

        String text) {

    static ChunkResponse from(AuthorizedChunk chunk) {
        return new ChunkResponse(chunk.chunkId(), chunk.documentKey(), chunk.versionNo(), chunk.title(), chunk.sectionPath(), chunk.charStart(),
                chunk.charEnd(), chunk.content());
    }
}

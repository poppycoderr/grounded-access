package io.groundedaccess.retrieval;

import java.util.UUID;

/**
 * A chunk the principal is authorized to see, with its position in one channel's ranking.
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

        RetrievalChannel channel,

        int rank,

        double score) {
}

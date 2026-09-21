package io.groundedaccess.retrieval;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One page of authorized chunks and the cursor for the next page, or {@code null} when this is the last one.
 */
public record ChunkPage(
        String policyVersion,

        List<AuthorizedChunk> chunks,

        @Nullable ChunkCursor next) {
}

package io.groundedaccess.api;

import io.groundedaccess.retrieval.ChunkCursor;
import io.groundedaccess.retrieval.ChunkPage;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A page of authorized chunks. {@code next} is passed back as {@code after} to read the following page; it is absent on the last page.
 */
public record ChunkPageResponse(
        String policyVersion,

        List<ChunkResponse> chunks,

        @Nullable String next) {

    static ChunkPageResponse from(ChunkPage page) {
        ChunkCursor next = page.next();
        return new ChunkPageResponse(page.policyVersion(), page.chunks().stream().map(ChunkResponse::from).toList(), next == null ? null : next.toString());
    }
}

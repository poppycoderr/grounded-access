package io.groundedaccess.api;

import io.groundedaccess.corpus.DocumentState;

import java.util.Locale;

/**
 * A document after a status change. {@code versionNo} is the version that retrieval returns while the document is active.
 */
public record DocumentResponse(
        String key,

        String status,

        int versionNo) {

    static DocumentResponse from(DocumentState state) {
        return new DocumentResponse(state.key(), state.status().name().toLowerCase(Locale.ROOT), state.versionNo());
    }
}

package io.groundedaccess.api;

import io.groundedaccess.retrieval.AuthorizedDocument;

/**
 * What a principal may learn about a document it is authorized to read.
 */
public record DocumentViewResponse(
        String key,

        String title,

        int versionNo) {

    static DocumentViewResponse from(AuthorizedDocument document) {
        return new DocumentViewResponse(document.key(), document.title(), document.versionNo());
    }
}

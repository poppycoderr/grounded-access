package io.groundedaccess.retrieval;

/**
 * The metadata of a document the principal is authorized to read: its key, the title of its current version and that version's number.
 */
public record AuthorizedDocument(
        String key,

        String title,

        int versionNo) {
}

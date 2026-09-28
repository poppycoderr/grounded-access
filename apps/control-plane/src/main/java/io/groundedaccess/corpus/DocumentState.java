package io.groundedaccess.corpus;

/**
 * A document as an administrator sees it after a status change: its key, status and the number of its active version.
 */
public record DocumentState(
        String key,

        DocumentStatus status,

        int versionNo) {
}

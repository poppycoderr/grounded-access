package io.groundedaccess.corpus;

/**
 * A chunk before it is stored: a span of the normalized document text plus the heading path it sits under.
 */
public record ChunkDraft(
        int ordinal,

        String sectionPath,

        int charStart,

        int charEnd,

        String content,

        int tokenCount) {
}

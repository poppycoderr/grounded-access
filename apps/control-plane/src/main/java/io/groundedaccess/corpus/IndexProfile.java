package io.groundedaccess.corpus;

/**
 * How the stored chunks and their vectors were made (ADR-0007). A query vector and a stored vector are comparable only when both come from
 * the same profile, so dense search reads only rows of the profile the query was embedded with. Profiles are compared field by field.
 */
public record IndexProfile(
        String embeddingModel,

        String embeddingRevision,

        int dimensions,

        String queryPrefix,

        String passagePrefix,

        boolean embeddingContext,

        String chunkerRelease,

        int chunkMaxWords,

        int chunkOverlapWords) {

    /**
     * For error messages and logs. The prefixes are instructions written by the model authors, not user text.
     */
    public String describe() {
        return "%s@%s, %d dimensions, query prefix \"%s\", passage prefix \"%s\", embedding context %s, chunker release %s, %d/%d words".formatted(
                embeddingModel, embeddingRevision, dimensions, queryPrefix, passagePrefix, embeddingContext ? "on" : "off", chunkerRelease, chunkMaxWords,
                chunkOverlapWords);
    }
}

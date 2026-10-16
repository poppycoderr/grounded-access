package io.groundedaccess.corpus;

import io.groundedaccess.authorization.AccessLabels;

import org.jspecify.annotations.Nullable;

/**
 * Everything about a document version except its content: who may read it, where and when it applies, and how it is shown. Two submissions
 * with the same content are the same version only if this is equal. The comparison is on the values themselves, sets as sets, so no encoding of
 * the values can make two different label sets look alike.
 */
record VersionMetadata(
        String title,

        @Nullable String sourceUri,

        AccessLabels labels,

        DocumentScope scope) {

    static VersionMetadata of(SourceDocument source) {
        return new VersionMetadata(source.title(), source.sourceUri(), source.labels(), source.scope());
    }
}

package io.groundedaccess.authorization;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A compiled authorization condition over the {@code chunk} table aliased as {@code c} and its {@code document_version} aliased as
 * {@code v}. The SQL is produced by {@link PolicyCompiler} only and all
 * principal values are bound parameters, never concatenated.
 */
public record AuthorizationPredicate(
        String sql,

        Map<String, @Nullable Object> parameters,

        String policyVersion) {
}

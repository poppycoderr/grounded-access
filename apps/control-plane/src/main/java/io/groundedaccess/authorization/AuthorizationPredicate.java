package io.groundedaccess.authorization;

import java.util.Map;

/**
 * A compiled authorization condition over the {@code chunk} table aliased as {@code c}. The SQL is produced by {@link PolicyCompiler} only and all
 * principal values are bound parameters, never concatenated.
 */
public record AuthorizationPredicate(
        String sql,

        Map<String, Object> parameters,

        String policyVersion) {
}

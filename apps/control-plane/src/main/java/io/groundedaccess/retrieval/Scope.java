package io.groundedaccess.retrieval;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Which documents apply to a query: those valid at {@code asOf} and applicable to {@code region}. Scope narrows what is relevant; it never
 * decides what a principal may read, and it is compiled separately from the authorization predicate so the two are never confused (ADR-0003,
 * ADR-0005). A null region applies no region filter. {@link #ANY} applies no scope at all and exists for the debug listing that the evaluation
 * compares with hand-labelled visibility.
 */
public record Scope(
        @Nullable Instant asOf,

        @Nullable String region) {

    public static final Scope ANY = new Scope(null, null);

    /**
     * Refers to the document version as {@code v}. Only the current version is ever a candidate, so {@code asOf} filters by its validity window
     * and never selects an older version.
     */
    private static final String SQL = """
            (v.valid_from is null or v.valid_from <= :scope_as_of) and (v.valid_to is null or :scope_as_of < v.valid_to) \
            and (cardinality(v.applies_to_regions) = 0 or cast(:scope_region as text) is null \
            or cast(:scope_region as text) = any(v.applies_to_regions))""";

    String sql() {
        return asOf == null ? "true" : SQL;
    }

    Map<String, @Nullable Object> parameters() {
        Map<String, @Nullable Object> parameters = new HashMap<>();
        if (asOf != null) {
            parameters.put("scope_as_of", asOf.atOffset(ZoneOffset.UTC));
            parameters.put("scope_region", region);
        }
        return parameters;
    }
}

package io.groundedaccess.corpus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Where and when a document version applies. This is scope, not authorization: an EU policy is not secret from a US employee, it just does not
 * apply to them. An empty region set applies everywhere; a missing bound leaves the validity window open on that side. Bounds are kept to the
 * microsecond, the precision PostgreSQL stores, so a stored scope compares equal to the one that was submitted.
 */
public record DocumentScope(
        Set<String> appliesToRegions,

        @Nullable Instant validFrom,

        @Nullable Instant validTo) {

    public static final DocumentScope EVERYWHERE_ALWAYS = new DocumentScope(Set.of(), null, null);

    public DocumentScope {
        appliesToRegions = Set.copyOf(appliesToRegions);
        validFrom = validFrom == null ? null : validFrom.truncatedTo(ChronoUnit.MICROS);
        validTo = validTo == null ? null : validTo.truncatedTo(ChronoUnit.MICROS);
        if (validFrom != null && validTo != null && !validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException("validFrom must be before validTo");
        }
    }
}

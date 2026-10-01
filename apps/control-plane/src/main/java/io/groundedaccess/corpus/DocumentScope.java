package io.groundedaccess.corpus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Where and when a document version applies. This is scope, not authorization: an EU policy is not secret from a US employee, it just does not
 * apply to them. An empty region set applies everywhere; a missing bound leaves the validity window open on that side.
 */
public record DocumentScope(
        Set<String> appliesToRegions,

        @Nullable Instant validFrom,

        @Nullable Instant validTo) {

    public static final DocumentScope EVERYWHERE_ALWAYS = new DocumentScope(Set.of(), null, null);

    public DocumentScope {
        appliesToRegions = Set.copyOf(appliesToRegions);
        if (validFrom != null && validTo != null && !validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException("validFrom must be before validTo");
        }
    }

    /**
     * Identifies the scope independently of the order regions were written in, so a submission with the same scope is recognised as unchanged.
     */
    public String sha256() {
        String canonical = String.join("\u001F", new TreeSet<>(appliesToRegions)) + "\n" + (validFrom == null ? "" : validFrom) + "\n"
                + (validTo == null ? "" : validTo);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}

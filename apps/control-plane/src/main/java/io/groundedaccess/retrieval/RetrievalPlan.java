package io.groundedaccess.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.jspecify.annotations.Nullable;

/**
 * Everything that decides how candidates are fetched and ordered for one request. Two results are comparable only if their plans are equal, so
 * the plan's hash is returned with every response and stored with every evaluation run. {@code rrfK} is present only when the strategy fuses.
 */
public record RetrievalPlan(
        RetrievalStrategy strategy,

        int k,

        int candidates,

        @Nullable Integer rrfK,

        boolean dedupeOverlaps) {

    static RetrievalPlan of(RetrievalStrategy strategy, int k, RetrievalProperties properties) {
        boolean fuses = strategy.usesSparse() && strategy.usesDense();
        return new RetrievalPlan(strategy, k, Math.max(properties.candidates(), k), fuses ? properties.rrfK() : null, true);
    }

    /**
     * The serialized form the hash is taken over. Field order and formatting are fixed here, not left to a JSON library, so the hash cannot change
     * with a library upgrade.
     */
    public String canonical() {
        return "{\"strategy\":\"%s\",\"k\":%d,\"candidates\":%d,\"rrfK\":%s,\"dedupeOverlaps\":%s}".formatted(strategy.wireName(), k, candidates, rrfK,
                dedupeOverlaps);
    }

    public String hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}

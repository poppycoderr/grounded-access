package io.groundedaccess.retrieval;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side retrieval settings. {@code candidates} is how many rows each channel contributes before fusion and deduplication; {@code rrfK} is
 * the constant of reciprocal rank fusion; {@code rerankCandidates} is how many of the fused candidates a reranking strategy sends to the
 * cross-encoder. {@code deadline} bounds the model calls of one search (ADR-0010) and {@code sqlTimeout} each retrieval statement; neither is
 * part of a plan. Clients choose only the strategy
 * and {@code k}.
 */
@ConfigurationProperties("ga.retrieval")
public record RetrievalProperties(
        int candidates,

        int rrfK,

        int rerankCandidates,

        boolean sparseContext,

        boolean rerankContext,

        Duration deadline,

        Duration sqlTimeout) {
}

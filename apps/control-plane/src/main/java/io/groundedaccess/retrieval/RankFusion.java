package io.groundedaccess.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Combines and trims ranked candidate lists. Everything here works on rows that already passed the authorization predicate in SQL; nothing is
 * filtered for access in this class.
 */
final class RankFusion {

    /** Ties are broken by stable keys, the same rule the SQL channels use, so equal scores never reorder between runs. */
    private static final Comparator<RetrievedChunk> ORDER = Comparator.comparingDouble(RetrievedChunk::score)
            .reversed()
            .thenComparing(RetrievedChunk::documentKey)
            .thenComparingInt(RetrievedChunk::versionNo)
            .thenComparingInt(RetrievedChunk::charStart);

    private RankFusion() {
    }

    /**
     * Reciprocal rank fusion: a chunk scores the sum of {@code 1 / (rrfK + rank)} over the channels that returned it. Only ranks are used, so the
     * incomparable scales of full-text scores and cosine similarity never meet. A chunk missing from a channel gets nothing from it.
     */
    static List<RetrievedChunk> reciprocalRank(List<RetrievedChunk> sparse, List<RetrievedChunk> dense, int rrfK) {
        Map<UUID, RetrievedChunk> merged = new LinkedHashMap<>();
        for (RetrievedChunk chunk : sparse) {
            merged.put(chunk.chunkId(), chunk);
        }
        for (RetrievedChunk chunk : dense) {
            merged.merge(chunk.chunkId(), chunk, RetrievedChunk::mergedWith);
        }
        List<RetrievedChunk> fused = new ArrayList<>();
        for (RetrievedChunk chunk : merged.values()) {
            double score = (chunk.sparseRank() == null ? 0 : 1.0 / (rrfK + chunk.sparseRank()))
                    + (chunk.denseRank() == null ? 0 : 1.0 / (rrfK + chunk.denseRank()));
            fused.add(chunk.ranked(0, score));
        }
        fused.sort(ORDER);
        return fused;
    }

    /**
     * Keeps the first {@code k} chunks of an ordered list and numbers them from 1. With {@code dedupeOverlaps}, a chunk whose span overlaps a
     * higher-ranked chunk of the same document version is dropped first: chunk overlap would otherwise spend two result slots on one passage.
     */
    static List<RetrievedChunk> top(List<RetrievedChunk> ordered, int k, boolean dedupeOverlaps) {
        List<RetrievedChunk> kept = new ArrayList<>();
        for (RetrievedChunk chunk : ordered) {
            if (kept.size() == k) {
                break;
            }
            if (!dedupeOverlaps || kept.stream().noneMatch(chunk::overlaps)) {
                kept.add(chunk.ranked(kept.size() + 1, chunk.score()));
            }
        }
        return kept;
    }
}

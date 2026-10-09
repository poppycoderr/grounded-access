package io.groundedaccess.answering;

import io.groundedaccess.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses which retrieved chunks go into the prompt and names them {@code S1..Sn} in rank order. Chunks are taken whole and in order until the
 * word budget is used up; the first chunk is always included, so a single long chunk cannot empty the context.
 */
public final class ContextBuilder {

    private final int maxWords;

    public ContextBuilder(int maxWords) {
        this.maxWords = maxWords;
    }

    public List<Evidence> build(List<RetrievedChunk> ranked) {
        List<Evidence> evidence = new ArrayList<>();
        int words = 0;
        for (RetrievedChunk chunk : ranked) {
            int size = chunk.content().isBlank() ? 0 : chunk.content().strip().split("\\s+").length;
            if (!evidence.isEmpty() && words + size > maxWords) {
                break;
            }
            evidence.add(new Evidence("S" + (evidence.size() + 1), chunk));
            words += size;
        }
        return evidence;
    }
}

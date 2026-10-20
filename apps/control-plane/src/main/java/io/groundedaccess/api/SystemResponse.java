package io.groundedaccess.api;

import io.groundedaccess.corpus.IndexProfiles;
import io.groundedaccess.retrieval.RetrievalProperties;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What this deployment is: its build, the index profile it searches, the reranker revision, the retrieval settings that are not part of a
 * plan, and the host it runs on. An evaluation run records it (ADR-0008). It describes no tenant, document, label or principal.
 */
public record SystemResponse(
        Build build,

        @Nullable IndexProfile indexProfile,

        Reranker reranker,

        RetrievalProperties retrieval,

        Host host) {

    /**
     * {@code commit} is the one the image was built from, or {@code unknown} when the build did not say.
     */
    public record Build(
            String version,

            String commit) {
    }

    /**
     * Null in the response when the model service cannot be reached or produces another profile than the stored one.
     */
    public record IndexProfile(
            UUID id,

            String embeddingModel,

            String embeddingRevision,

            int dimensions,

            String queryPrefix,

            String passagePrefix,

            boolean embeddingContext,

            String chunkerRelease,

            int chunkMaxWords,

            int chunkOverlapWords) {

        static IndexProfile from(IndexProfiles.Established established) {
            var p = established.profile();
            return new IndexProfile(established.id(), p.embeddingModel(), p.embeddingRevision(), p.dimensions(), p.queryPrefix(), p.passagePrefix(),
                    p.embeddingContext(), p.chunkerRelease(), p.chunkMaxWords(), p.chunkOverlapWords());
        }
    }

    /**
     * {@code revision} is null when the model service cannot be reached.
     */
    public record Reranker(
            String model,

            @Nullable String revision) {
    }

    public record Host(
            String cpu,

            int processors) {
    }
}

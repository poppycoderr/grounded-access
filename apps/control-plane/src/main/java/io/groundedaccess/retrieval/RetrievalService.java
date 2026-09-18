package io.groundedaccess.retrieval;

import io.groundedaccess.authorization.AuthorizationPredicate;
import io.groundedaccess.authorization.PolicyCompiler;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.InputType;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Runs one retrieval strategy for a principal. The authorization predicate is compiled once per request and shared by every channel.
 */
@Service
public class RetrievalService {

    private final PolicyCompiler policyCompiler;

    private final AuthorizedChunkQuery chunks;

    private final EmbeddingClient embeddings;

    public RetrievalService(PolicyCompiler policyCompiler, AuthorizedChunkQuery chunks, EmbeddingClient embeddings) {
        this.policyCompiler = policyCompiler;
        this.chunks = chunks;
        this.embeddings = embeddings;
    }

    public RetrievalResult search(Principal principal, String query, RetrievalStrategy strategy, int limit) {
        AuthorizationPredicate predicate = policyCompiler.compile(principal);
        List<RetrievedChunk> ranked = switch (strategy) {
            case SPARSE_ONLY -> chunks.sparse(query, predicate, limit);
            case DENSE_ONLY -> chunks.dense(embeddings.embed(List.of(query), InputType.QUERY).vectors().getFirst(), predicate, limit);
        };
        return new RetrievalResult(strategy, predicate.policyVersion(), ranked);
    }
}

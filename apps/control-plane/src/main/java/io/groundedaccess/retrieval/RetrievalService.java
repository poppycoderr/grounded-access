package io.groundedaccess.retrieval;

import io.groundedaccess.authorization.AuthorizationPredicate;
import io.groundedaccess.authorization.PolicyCompiler;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.InputType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Runs one retrieval strategy for a principal. The authorization predicate is compiled once per request and shared by every channel.
 */
@Service
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalService {

    public static final String DENSE_UNAVAILABLE = "dense_unavailable";

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final PolicyCompiler policyCompiler;

    private final AuthorizedChunkQuery chunks;

    private final EmbeddingClient embeddings;

    private final RetrievalProperties properties;

    private final Clock clock = Clock.systemUTC();

    public RetrievalService(PolicyCompiler policyCompiler, AuthorizedChunkQuery chunks, EmbeddingClient embeddings, RetrievalProperties properties) {
        this.policyCompiler = policyCompiler;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    public ChunkPage list(Principal principal, Scope scope, @Nullable ChunkCursor after, int limit) {
        AuthorizationPredicate predicate = policyCompiler.compile(principal);
        List<AuthorizedChunk> rows = chunks.list(predicate, scope, after, limit + 1);
        boolean more = rows.size() > limit;
        List<AuthorizedChunk> page = more ? rows.subList(0, limit) : rows;
        return new ChunkPage(predicate.policyVersion(), page, more ? page.getLast().cursor() : null);
    }

    /**
     * The scope of a request: the moment and region it asks about, defaulting to now and to the principal's region. Scope comes from the request
     * because it is not authorization: asking about another region or another date changes what is relevant, never what the principal may read.
     */
    public Scope scope(Principal principal, @Nullable Instant asOf, @Nullable String region) {
        return new Scope(asOf != null ? asOf : clock.instant(), region != null ? region : principal.region());
    }

    /**
     * Both channels run with the same compiled predicate. A hybrid search whose query cannot be embedded falls back to the sparse channel and says
     * so in {@code degraded}; a dense-only search has nothing to fall back to and fails.
     */
    public RetrievalResult search(Principal principal, String query, RetrievalStrategy strategy, int k, Scope scope) {
        AuthorizationPredicate predicate = policyCompiler.compile(principal);
        RetrievalPlan plan = RetrievalPlan.of(strategy, k, properties);
        List<String> degraded = new ArrayList<>();
        List<RetrievedChunk> sparse = strategy.usesSparse() ? chunks.sparse(query, predicate, scope, plan.candidates()) : List.of();
        List<RetrievedChunk> dense = List.of();
        if (strategy.usesDense()) {
            try {
                dense = chunks.dense(embeddings.embed(List.of(query), InputType.QUERY).vectors().getFirst(), predicate, scope, plan.candidates());
            } catch (RestClientException e) {
                if (!strategy.usesSparse() || e instanceof HttpClientErrorException) {
                    throw e;
                }
                log.warn("Embedding the query failed, answering from the sparse channel only: {}", e.toString());
                degraded.add(DENSE_UNAVAILABLE);
            }
        }
        Integer rrfK = plan.rrfK();
        List<RetrievedChunk> ordered = rrfK == null || !degraded.isEmpty() ? (strategy.usesSparse() ? sparse : dense) : RankFusion.reciprocalRank(sparse, dense, rrfK);
        return new RetrievalResult(plan, predicate.policyVersion(), RankFusion.top(ordered, plan.k(), plan.dedupeOverlaps()), List.copyOf(degraded), scope);
    }
}

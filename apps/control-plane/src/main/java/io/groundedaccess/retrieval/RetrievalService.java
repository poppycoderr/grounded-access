package io.groundedaccess.retrieval;

import io.groundedaccess.audit.AuditTrail;
import io.groundedaccess.audit.SearchRecord;
import io.groundedaccess.authorization.AuthorizationPredicate;
import io.groundedaccess.authorization.PolicyCompiler;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;
import io.groundedaccess.telemetry.TraceContext;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private final AuditTrail audit;

    private final Clock clock = Clock.systemUTC();

    public RetrievalService(PolicyCompiler policyCompiler, AuthorizedChunkQuery chunks, EmbeddingClient embeddings, RetrievalProperties properties,
            AuditTrail audit) {
        this.policyCompiler = policyCompiler;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.properties = properties;
        this.audit = audit;
    }

    public ChunkPage list(Principal principal, Scope scope, @Nullable ChunkCursor after, int limit) {
        AuthorizationPredicate predicate = policyCompiler.compile(principal);
        List<AuthorizedChunk> rows = chunks.list(predicate, scope, after, limit + 1);
        boolean more = rows.size() > limit;
        List<AuthorizedChunk> page = more ? rows.subList(0, limit) : rows;
        audit.recordListing(principal, TraceContext.current(), predicate.policyVersion(), page.size(), scope.asOf() != null);
        return new ChunkPage(predicate.policyVersion(), page, more ? page.getLast().cursor() : null);
    }

    /**
     * The metadata of one document, under the same predicate as search. Scope is not applied: this answers whether the principal may read the
     * document, not whether it applies to a region or a date. Both outcomes are audited.
     */
    public Optional<AuthorizedDocument> document(Principal principal, String key) {
        AuthorizationPredicate predicate = policyCompiler.compile(principal);
        Optional<AuthorizedDocument> document = chunks.document(key, predicate);
        audit.recordDocumentRead(principal, TraceContext.current(), key, predicate.policyVersion(), document.isPresent());
        return document;
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
     * so in {@code degraded}; a dense-only search has nothing to fall back to and fails. The execution record and audit event are written before
     * the result is returned: if that write fails, the search fails and nothing is disclosed.
     */
    public RetrievalResult search(Principal principal, String query, RetrievalStrategy strategy, int k, Scope scope) {
        AuthorizationPredicate predicate = policyCompiler.compile(principal);
        RetrievalPlan plan = RetrievalPlan.of(strategy, k, properties);
        List<String> degraded = new ArrayList<>();
        long started = System.nanoTime();
        List<RetrievedChunk> sparse = strategy.usesSparse() ? chunks.sparse(query, predicate, scope, plan.candidates()) : List.of();
        long sparseDone = System.nanoTime();
        List<RetrievedChunk> dense = List.of();
        String embeddingModel = null;
        if (strategy.usesDense()) {
            try {
                Embeddings embedded = embeddings.embed(List.of(query), InputType.QUERY);
                embeddingModel = embedded.modelId();
                dense = chunks.dense(embedded.vectors().getFirst(), predicate, scope, plan.candidates());
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
        List<RetrievedChunk> results = RankFusion.top(ordered, plan.k(), plan.dedupeOverlaps());
        long finished = System.nanoTime();
        String traceId = TraceContext.current();
        List<String> documents = results.stream().map(chunk -> chunk.documentKey() + "@v" + chunk.versionNo()).distinct().toList();
        UUID executionId = audit.recordSearch(new SearchRecord(principal.tenantId(), principal.subject(), traceId, strategy.wireName(), k, plan.hash(),
                plan.canonical(), predicate.policyVersion(), embeddingModel, degraded, documents, results.size(), scope.asOf(), scope.region(),
                millis(finished - started), millis(sparseDone - started), millis(finished - sparseDone)));
        return new RetrievalResult(plan, predicate.policyVersion(), results, List.copyOf(degraded), scope, executionId, traceId);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }
}

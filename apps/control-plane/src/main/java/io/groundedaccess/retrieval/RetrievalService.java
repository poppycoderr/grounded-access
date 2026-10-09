package io.groundedaccess.retrieval;

import io.groundedaccess.corpus.DocumentChunker;
import io.groundedaccess.audit.AuditTrail;
import io.groundedaccess.audit.SearchRecord;
import io.groundedaccess.authorization.AuthorizationPredicate;
import io.groundedaccess.authorization.PolicyCompiler;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;
import io.groundedaccess.modelclient.RerankClient;
import io.groundedaccess.modelclient.RerankScores;
import io.groundedaccess.telemetry.Failures;
import io.groundedaccess.telemetry.SpanAttribute;
import io.groundedaccess.telemetry.Spans;
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

    public static final String RERANK_UNAVAILABLE = "rerank_unavailable";

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final PolicyCompiler policyCompiler;

    private final AuthorizedChunkQuery chunks;

    private final EmbeddingClient embeddings;

    private final RerankClient reranker;

    private final RetrievalProperties properties;

    private final AuditTrail audit;

    private final Spans spans;

    private final Clock clock = Clock.systemUTC();

    public RetrievalService(PolicyCompiler policyCompiler, AuthorizedChunkQuery chunks, EmbeddingClient embeddings, RerankClient reranker,
            RetrievalProperties properties, AuditTrail audit, Spans spans) {
        this.policyCompiler = policyCompiler;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.reranker = reranker;
        this.properties = properties;
        this.audit = audit;
        this.spans = spans;
        spans.expect(List.of(DENSE_UNAVAILABLE, RERANK_UNAVAILABLE), List.of());
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
     * so in {@code degraded}; a dense-only search has nothing to fall back to and fails. A reranking strategy sends the top fused candidates,
     * all of them already authorized, to the cross-encoder; if that call fails or times out the fused order is returned and {@code degraded}
     * says so. The execution record and audit event are written before
     * the result is returned: if that write fails, the search fails and nothing is disclosed.
     */
    public RetrievalResult search(Principal principal, String query, RetrievalStrategy strategy, int k, Scope scope) {
        return spans.in("retrieval.search", span -> {
            RetrievalResult result = searchTraced(principal, query, strategy, k, scope);
            span.set(SpanAttribute.RETRIEVAL_STRATEGY, strategy.wireName());
            span.set(SpanAttribute.RETRIEVAL_K, k);
            span.set(SpanAttribute.PIPELINE_CONFIG_HASH, result.plan().hash());
            span.set(SpanAttribute.POLICY_VERSION, result.policyVersion());
            span.set(SpanAttribute.RETRIEVAL_RESULTS, result.chunks().size());
            if (!result.degraded().isEmpty()) {
                span.set(SpanAttribute.DEGRADED, String.join(",", result.degraded()));
                result.degraded().forEach(spans::degraded);
            }
            return result;
        });
    }

    private RetrievalResult searchTraced(Principal principal, String query, RetrievalStrategy strategy, int k, Scope scope) {
        AuthorizationPredicate predicate = spans.in("policy.compile", span -> {
            AuthorizationPredicate compiled = policyCompiler.compile(principal);
            span.set(SpanAttribute.POLICY_VERSION, compiled.policyVersion());
            return compiled;
        });
        RetrievalPlan plan = RetrievalPlan.of(strategy, k, properties, reranker.modelName());
        List<String> degraded = new ArrayList<>();
        long started = System.nanoTime();
        List<RetrievedChunk> sparse = strategy.usesSparse() ? spans.in("retrieval.sparse", span -> {
            List<RetrievedChunk> rows = chunks.sparse(query, predicate, scope, plan.candidates(), plan.sparseContext());
            span.set(SpanAttribute.RETRIEVAL_CANDIDATES, rows.size());
            return rows;
        }) : List.of();
        long sparseDone = System.nanoTime();
        List<RetrievedChunk> dense = List.of();
        String embeddingModel = null;
        boolean denseAvailable = strategy.usesDense();
        if (strategy.usesDense()) {
            try {
                DenseCandidates found = spans.in("retrieval.dense", span -> {
                    Embeddings embedded = spans.in("model.embed", model -> {
                        Embeddings vectors = embeddings.embed(List.of(query), InputType.QUERY);
                        model.set(SpanAttribute.MODEL_NAME, vectors.modelId());
                        return vectors;
                    });
                    List<RetrievedChunk> rows = chunks.dense(embedded.vectors().getFirst(), predicate, scope, plan.candidates());
                    span.set(SpanAttribute.RETRIEVAL_CANDIDATES, rows.size());
                    return new DenseCandidates(embedded.modelId(), rows);
                });
                embeddingModel = found.model();
                dense = found.rows();
            } catch (RestClientException e) {
                if (!strategy.usesSparse() || e instanceof HttpClientErrorException) {
                    throw e;
                }
                log.warn("Embedding the query failed, answering from the sparse channel only: {}", Failures.describe(e));
                degraded.add(DENSE_UNAVAILABLE);
                denseAvailable = false;
            }
        }
        long denseDone = System.nanoTime();
        Integer rrfK = plan.rrfK();
        List<RetrievedChunk> ordered;
        if (rrfK != null && denseAvailable) {
            List<RetrievedChunk> sparseRows = sparse;
            List<RetrievedChunk> denseRows = dense;
            ordered = spans.in("retrieval.fusion", span -> {
                List<RetrievedChunk> fused = RankFusion.reciprocalRank(sparseRows, denseRows, rrfK);
                span.set(SpanAttribute.RETRIEVAL_CANDIDATES, fused.size());
                return fused;
            });
        } else {
            ordered = strategy.usesSparse() ? sparse : dense;
        }
        List<RetrievedChunk> results;
        String rerankerModel = null;
        Integer rerankCandidates = plan.rerankCandidates();
        if (rerankCandidates != null) {
            List<RetrievedChunk> pool = RankFusion.top(ordered, rerankCandidates, plan.dedupeOverlaps());
            if (!pool.isEmpty()) {
                List<RetrievedChunk> candidates = pool;
                try {
                    Reranked reranked = spans.in("rerank", span -> {
                        span.set(SpanAttribute.RETRIEVAL_CANDIDATES, candidates.size());
                        RerankScores scores = spans.in("model.rerank", model -> {
                            RerankScores scored = reranker.score(query, candidates.stream()
                                    .map(chunk -> plan.rerankContext() ? DocumentChunker.withContext(chunk.sectionPath(), chunk.content()) : chunk.content())
                                    .toList());
                            model.set(SpanAttribute.MODEL_NAME, scored.modelId());
                            return scored;
                        });
                        return new Reranked(scores.modelId(), RankFusion.rerank(candidates, scores.scores()));
                    });
                    rerankerModel = reranked.model();
                    pool = reranked.rows();
                } catch (RestClientException e) {
                    log.warn("Reranking failed, answering in the fused order: {}", Failures.describe(e));
                    degraded.add(RERANK_UNAVAILABLE);
                }
            }
            results = RankFusion.top(pool, plan.k(), false);
        } else {
            results = RankFusion.top(ordered, plan.k(), plan.dedupeOverlaps());
        }
        long finished = System.nanoTime();
        String traceId = TraceContext.current();
        List<String> documents = results.stream().map(chunk -> chunk.documentKey() + "@v" + chunk.versionNo()).distinct().toList();
        UUID executionId = audit.recordSearch(new SearchRecord(principal.tenantId(), principal.subject(), traceId, strategy.wireName(), k, plan.hash(),
                plan.canonical(), predicate.policyVersion(), embeddingModel, rerankerModel, degraded, documents, results.size(), scope.asOf(),
                scope.region(), millis(finished - started), millis(sparseDone - started), millis(denseDone - sparseDone), millis(finished - denseDone)));
        return new RetrievalResult(plan, predicate.policyVersion(), results, List.copyOf(degraded), scope, executionId, traceId);
    }

    private record DenseCandidates(
            String model,

            List<RetrievedChunk> rows) {
    }

    private record Reranked(
            String model,

            List<RetrievedChunk> rows) {
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }
}

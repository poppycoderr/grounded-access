package io.groundedaccess.audit;

import io.groundedaccess.authorization.TextArrays;
import io.groundedaccess.identity.Principal;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes audit events and query execution records. Every method names the attributes it stores, and the JSON is built in SQL from typed
 * parameters, so only identifiers, counts and versions can be recorded. A failed write raises {@link AuditUnavailableException}; callers let it
 * propagate, which rolls back the surrounding transaction or stops the response.
 */
@Repository
public class AuditTrail {

    private static final String EVENT = """
            insert into audit_event (id, tenant_id, principal_id, action, resource_type, resource_id, decision, policy_version, trace_id, attributes)
            values (:id, :tenant, :principal, :action, :resourceType, :resourceId, :decision, :policyVersion, :trace, %s)
            """;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    public AuditTrail(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * Stores the execution record and its audit event together and returns the execution id.
     */
    public UUID recordSearch(SearchRecord search) {
        UUID executionId = UUID.randomUUID();
        guarded(() -> transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            insert into query_execution (id, tenant_id, principal_id, trace_id, plan_hash, plan, policy_version, embedding_model,
                                reranker_model, status, degraded_reasons, result_count, latency_ms)
                            values (:id, :tenant, :principal, :trace, :planHash, cast(:plan as jsonb), :policyVersion, :model, :reranker, :status,
                                cast(:degraded as text[]), :count,
                                jsonb_build_object('total', cast(:totalMs as bigint), 'sparse', cast(:sparseMs as bigint), 'dense', cast(:denseMs as bigint),
                                    'rerank', cast(:rerankMs as bigint)))
                            """)
                    .param("id", executionId)
                    .param("tenant", search.tenantId())
                    .param("principal", search.principalId())
                    .param("trace", search.traceId())
                    .param("planHash", search.planHash())
                    .param("plan", search.planJson())
                    .param("policyVersion", search.policyVersion())
                    .param("model", search.embeddingModel())
                    .param("reranker", search.rerankerModel())
                    .param("rerankMs", search.rerankMs())
                    .param("status", search.degradedReasons().isEmpty() ? "ok" : "degraded")
                    .param("degraded", TextArrays.literal(search.degradedReasons()))
                    .param("count", search.resultCount())
                    .param("totalMs", search.totalMs())
                    .param("sparseMs", search.sparseMs())
                    .param("denseMs", search.denseMs())
                    .update();
            jdbc.sql(EVENT.formatted("""
                            jsonb_build_object('strategy', cast(:strategy as text), 'k', cast(:k as int), 'planHash', cast(:planHash as text),
                                'resultCount', cast(:count as int), 'documents', to_jsonb(cast(:documents as text[])),
                                'degraded', to_jsonb(cast(:degraded as text[])), 'asOf', cast(:asOf as timestamptz), 'region', cast(:region as text))"""))
                    .param("id", UUID.randomUUID())
                    .param("tenant", search.tenantId())
                    .param("principal", search.principalId())
                    .param("action", "retrieval.search")
                    .param("resourceType", "query_execution")
                    .param("resourceId", executionId.toString())
                    .param("decision", "allow")
                    .param("policyVersion", search.policyVersion())
                    .param("trace", search.traceId())
                    .param("strategy", search.strategy())
                    .param("k", search.k())
                    .param("planHash", search.planHash())
                    .param("count", search.resultCount())
                    .param("documents", TextArrays.literal(search.documents()))
                    .param("degraded", TextArrays.literal(search.degradedReasons()))
                    .param("asOf", search.asOf() == null ? null : search.asOf().atOffset(ZoneOffset.UTC))
                    .param("region", search.region())
                    .update();
        }));
        return executionId;
    }

    /**
     * The debug listing discloses every chunk a principal may read, so each page is recorded with its size and whether scope was applied.
     */
    public void recordListing(Principal principal, String traceId, String policyVersion, int chunkCount, boolean scoped) {
        event(principal, traceId, "retrieval.list_chunks", "chunk_listing", principal.tenantId(), "allow", policyVersion,
                "jsonb_build_object('chunkCount', cast(:count as int), 'scoped', cast(:scoped as boolean))", Map.of("count", chunkCount, "scoped", scoped));
    }

    public void recordIngestionSubmitted(Principal principal, String traceId, UUID jobId, List<String> documentKeys) {
        event(principal, traceId, "ingestion.submit", "ingestion_job", jobId.toString(), "allow", null,
                "jsonb_build_object('documents', to_jsonb(cast(:documents as text[])))", Map.of("documents", TextArrays.literal(documentKeys)));
    }

    /**
     * A status change or deletion of a document. {@code outcome} is the status after the change.
     */
    public void recordDocumentChange(Principal principal, String traceId, String action, String documentKey, String outcome) {
        event(principal, traceId, action, "document", documentKey, "allow", null, "jsonb_build_object('status', cast(:status as text))",
                Map.of("status", outcome));
    }

    /**
     * A read of one document's metadata. {@code deny} means the document was not visible to the principal; the event does not say whether it
     * exists, and neither does the response. The key must already be validated, because it is stored as the resource id.
     */
    public void recordDocumentRead(Principal principal, String traceId, String documentKey, String policyVersion, boolean visible) {
        event(principal, traceId, "document.read", "document", documentKey, visible ? "allow" : "deny", policyVersion, "jsonb_build_object()", Map.of());
    }

    /**
     * An execution record is visible to the principal that made the request and to nobody else; anything else looks like a missing record.
     */
    public Optional<QueryExecution> findExecution(Principal principal, UUID id) {
        return jdbc.sql("""
                        select id, trace_id, plan_hash, policy_version, embedding_model, status, degraded_reasons, result_count,
                            (latency_ms ->> 'total')::bigint, created_at, reranker_model
                        from query_execution where id = :id and tenant_id = :tenant and principal_id = :principal
                        """)
                .param("id", id)
                .param("tenant", principal.tenantId())
                .param("principal", principal.subject())
                .query((rs, i) -> new QueryExecution(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), List.of((String[]) rs.getArray(7).getArray()), rs.getInt(8), rs.getLong(9), rs.getTimestamp(10).toInstant(),
                        rs.getString(11)))
                .optional();
    }

    private void event(Principal principal, String traceId, String action, String resourceType, String resourceId, String decision,
            @Nullable String policyVersion,
            String attributes, Map<String, Object> values) {
        guarded(() -> jdbc.sql(EVENT.formatted(attributes))
                .param("id", UUID.randomUUID())
                .param("tenant", principal.tenantId())
                .param("principal", principal.subject())
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("decision", decision)
                .param("policyVersion", policyVersion)
                .param("trace", traceId)
                .params(values)
                .update());
    }

    private static void guarded(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            throw new AuditUnavailableException(e);
        }
    }
}

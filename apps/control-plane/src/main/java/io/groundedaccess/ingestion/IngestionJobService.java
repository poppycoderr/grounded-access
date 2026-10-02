package io.groundedaccess.ingestion;

import io.groundedaccess.audit.AuditTrail;
import io.groundedaccess.corpus.SourceDocument;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.telemetry.TraceContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts ingestion requests as jobs and reports on them. Jobs belong to a tenant: a job from another tenant is reported as missing.
 */
@Service
@EnableConfigurationProperties(IngestionWorkerProperties.class)
public class IngestionJobService {

    private final IngestionJobStore store;

    private final TransactionTemplate transactions;

    private final IngestionWorkerProperties properties;

    private final AuditTrail audit;

    public IngestionJobService(IngestionJobStore store, TransactionTemplate transactions, IngestionWorkerProperties properties,
            AuditTrail audit) {
        this.store = store;
        this.transactions = transactions;
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * Stores the documents, queues a job for them and records who submitted it, all in one transaction. Nothing is parsed or embedded here, so the call is fast and does not need the model service.
     */
    public IngestionJob submit(Principal principal, List<SourceDocument> documents) {
        UUID id = Objects.requireNonNull(transactions.execute(status -> {
            UUID job = store.insert(principal.tenantId(), principal.subject(), documents, properties.maxAttempts());
            audit.recordIngestionSubmitted(principal, TraceContext.current(), job, documents.stream().map(SourceDocument::key).toList());
            return job;
        }));
        return store.find(principal.tenantId(), id).orElseThrow();
    }

    public Optional<IngestionJob> find(Principal principal, UUID id) {
        return store.find(principal.tenantId(), id);
    }
}

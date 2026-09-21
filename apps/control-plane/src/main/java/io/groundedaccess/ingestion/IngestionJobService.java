package io.groundedaccess.ingestion;

import io.groundedaccess.corpus.SourceDocument;
import io.groundedaccess.identity.Principal;

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

    public IngestionJobService(IngestionJobStore store, TransactionTemplate transactions, IngestionWorkerProperties properties) {
        this.store = store;
        this.transactions = transactions;
        this.properties = properties;
    }

    /**
     * Stores the documents and queues a job for them. Nothing is parsed or embedded here, so the call is fast and does not need the model service.
     */
    public IngestionJob submit(Principal principal, List<SourceDocument> documents) {
        UUID id = Objects.requireNonNull(
                transactions.execute(status -> store.insert(principal.tenantId(), principal.subject(), documents, properties.maxAttempts())));
        return store.find(principal.tenantId(), id).orElseThrow();
    }

    public Optional<IngestionJob> find(Principal principal, UUID id) {
        return store.find(principal.tenantId(), id);
    }
}

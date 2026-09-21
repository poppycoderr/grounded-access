package io.groundedaccess.ingestion;

import io.groundedaccess.corpus.IngestionResult;
import io.groundedaccess.corpus.IngestionService;
import io.groundedaccess.corpus.SourceDocument;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Runs queued ingestion jobs one document at a time. Progress is recorded after every document, so a retry or a takeover resumes where the last
 * attempt stopped instead of starting over.
 *
 * <p>Delivery is at least once: a document written just before a worker dies is ingested again by the next attempt. That is safe because
 * ingestion is idempotent by content hash; the second pass counts it as unchanged.
 */
@Component
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final IngestionJobStore store;

    private final IngestionService ingestion;

    private final IngestionWorkerProperties properties;

    public IngestionWorker(IngestionJobStore store, IngestionService ingestion, IngestionWorkerProperties properties) {
        this.store = store;
        this.ingestion = ingestion;
        this.properties = properties;
    }

    /**
     * Runs every job that is runnable now and returns how many were claimed. Jobs waiting for a retry delay are left for a later call.
     */
    public int drain() {
        int claimed = 0;
        while (runOnce()) {
            claimed++;
        }
        return claimed;
    }

    /**
     * Claims and runs at most one job. Returns false when nothing was runnable.
     */
    public boolean runOnce() {
        int abandoned = store.failAbandoned();
        if (abandoned > 0) {
            log.warn("Marked {} ingestion job(s) failed after their worker stopped on the last attempt", abandoned);
        }
        Optional<IngestionJobStore.ClaimedJob> claimed = store.claim(properties.lease());
        claimed.ifPresent(this::run);
        return claimed.isPresent();
    }

    private void run(IngestionJobStore.ClaimedJob job) {
        try {
            for (int ordinal = job.processed(); ordinal < job.documentCount(); ordinal++) {
                SourceDocument document = store.document(job.id(), ordinal).orElseThrow(() -> new IllegalStateException("job document is missing"));
                IngestionResult result = ingestion.ingest(job.tenantId(), List.of(document));
                if (!store.recordProgress(job, result, properties.lease())) {
                    log.warn("Ingestion job {} attempt {} lost its lease; the next claim resumes it", job.id(), job.attempt());
                    return;
                }
            }
            if (store.finish(job, IngestionJobStatus.SUCCEEDED, null)) {
                log.info("Ingestion job {} succeeded on attempt {}", job.id(), job.attempt());
            }
        } catch (RuntimeException e) {
            fail(job, e);
        }
    }

    private void fail(IngestionJobStore.ClaimedJob job, RuntimeException cause) {
        String retryable = retryableCode(cause);
        if (retryable != null && job.attempt() < job.maxAttempts()) {
            Duration delay = properties.retryBackoff().multipliedBy(1L << Math.min(job.attempt() - 1, 10));
            log.warn("Ingestion job {} attempt {} of {} failed with {}; retrying in {}: {}", job.id(), job.attempt(), job.maxAttempts(), retryable, delay,
                    cause.toString());
            store.retryLater(job, retryable, delay);
            return;
        }
        String code = retryable != null ? retryable : permanentCode(cause);
        log.warn("Ingestion job {} failed on attempt {} with {}: {}", job.id(), job.attempt(), code, cause.toString());
        store.finish(job, IngestionJobStatus.FAILED, code);
    }

    /**
     * Failures that may pass on their own: the model service being down or slow, or a transient database error. A 4xx from the model service
     * means the request itself is wrong and will fail again.
     */
    private static @Nullable String retryableCode(RuntimeException cause) {
        if (cause instanceof RestClientException && !(cause instanceof HttpClientErrorException)) {
            return "MODEL_SERVICE_UNAVAILABLE";
        }
        if (cause instanceof TransientDataAccessException) {
            return "DATABASE_UNAVAILABLE";
        }
        return null;
    }

    private static String permanentCode(RuntimeException cause) {
        return cause instanceof HttpClientErrorException ? "MODEL_SERVICE_REJECTED" : "INTERNAL_ERROR";
    }
}

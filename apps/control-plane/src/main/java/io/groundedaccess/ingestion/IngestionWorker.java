package io.groundedaccess.ingestion;

import io.groundedaccess.telemetry.Failures;
import io.groundedaccess.corpus.IngestionResult;
import io.groundedaccess.corpus.IndexProfileMismatchException;
import io.groundedaccess.corpus.IngestionService;
import io.groundedaccess.corpus.SourceDocument;
import io.groundedaccess.corpus.WriteFence;
import io.groundedaccess.modelclient.InvalidModelResponseException;
import io.groundedaccess.modelclient.TextTooLongException;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Runs queued ingestion jobs one document at a time, so a retry or a takeover resumes where the last attempt stopped instead of starting over.
 *
 * <p>
 * Each document is committed in one transaction with the job's progress, and that transaction first checks that this worker still owns the
 * job: the claimed attempt is current and its lease has not expired. A worker that lost the job, because it stalled until another worker took
 * over or until the lease ran out, therefore writes nothing. While a job runs, its lease is renewed in the background, so a single slow document
 * does not lose the job for a worker that is still alive.
 */
@Component
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final IngestionJobStore store;

    private final IngestionService ingestion;

    private final IngestionWorkerProperties properties;

    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ingestion-lease-renewal");
        thread.setDaemon(true);
        return thread;
    });

    public IngestionWorker(IngestionJobStore store, IngestionService ingestion, IngestionWorkerProperties properties) {
        this.store = store;
        this.ingestion = ingestion;
        this.properties = properties;
    }

    @PreDestroy
    void stopRenewals() {
        renewals.shutdownNow();
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
        long every = Math.max(1, properties.lease().toMillis() / 3);
        ScheduledFuture<?> renewal = renewals.scheduleAtFixedRate(() -> renew(job), every, every, TimeUnit.MILLISECONDS);
        try {
            for (int ordinal = job.processed(); ordinal < job.documentCount(); ordinal++) {
                SourceDocument document = store.document(job.id(), ordinal).orElseThrow(() -> new IllegalStateException("job document is missing"));
                ingestion.ingest(job.tenantId(), document, fence(job));
            }
            if (store.finish(job, IngestionJobStatus.SUCCEEDED, null)) {
                log.info("Ingestion job {} succeeded on attempt {}", job.id(), job.attempt());
            }
        } catch (LeaseLostException e) {
            log.warn("Ingestion job {} attempt {} lost its lease before writing; the next claim resumes it", job.id(), job.attempt());
        } catch (RuntimeException e) {
            fail(job, e);
        } finally {
            renewal.cancel(false);
        }
    }

    private WriteFence fence(IngestionJobStore.ClaimedJob job) {
        return new WriteFence() {

            @Override
            public void verify() {
                if (!store.lockForCommit(job)) {
                    throw new LeaseLostException();
                }
            }

            @Override
            public void record(IngestionResult result) {
                if (!store.recordProgress(job, result, properties.lease())) {
                    throw new IllegalStateException("the job row is locked by this transaction, so its progress must be recordable");
                }
            }
        };
    }

    private void renew(IngestionJobStore.ClaimedJob job) {
        try {
            store.renewLease(job, properties.lease());
        } catch (RuntimeException e) {
            log.warn("Renewing the lease of ingestion job {} failed: {}", job.id(), Failures.describe(e));
        }
    }

    /**
     * The worker no longer owns the job, so it stops without touching the job or the corpus.
     */
    private static final class LeaseLostException extends RuntimeException {

        LeaseLostException() {
            super("the worker no longer owns the job", null, false, false);
        }
    }

    private void fail(IngestionJobStore.ClaimedJob job, RuntimeException cause) {
        String retryable = retryableCode(cause);
        if (retryable != null && job.attempt() < job.maxAttempts()) {
            Duration delay = properties.retryBackoff().multipliedBy(1L << Math.min(job.attempt() - 1, 10));
            log.warn("Ingestion job {} attempt {} of {} failed with {}; retrying in {}: {}", job.id(), job.attempt(), job.maxAttempts(), retryable, delay,
                    Failures.describe(cause));
            store.retryLater(job, retryable, delay);
            return;
        }
        String code = retryable != null ? retryable : permanentCode(cause);
        log.warn("Ingestion job {} failed on attempt {} with {}: {}", job.id(), job.attempt(), code, Failures.describe(cause));
        store.finish(job, IngestionJobStatus.FAILED, code);
    }

    /**
     * Failures that may pass on their own: the model service being down or slow, or a transient database error. A 4xx from the model service
     * means the request itself is wrong and will fail again; a response that breaks the contract or comes from another index profile means the
     * deployment is wrong, and retrying would only fail again.
     */
    private static @Nullable String retryableCode(RuntimeException cause) {
        if (cause instanceof RestClientException && !(cause instanceof HttpClientErrorException) && !(cause instanceof InvalidModelResponseException)
                && !(cause instanceof TextTooLongException)) {
            return "MODEL_SERVICE_UNAVAILABLE";
        }
        if (cause instanceof TransientDataAccessException) {
            return "DATABASE_UNAVAILABLE";
        }
        return null;
    }

    private static String permanentCode(RuntimeException cause) {
        return switch (cause) {
            case HttpClientErrorException rejected -> "MODEL_SERVICE_REJECTED";
            case InvalidModelResponseException invalid -> "MODEL_RESPONSE_INVALID";
            // The chunk budget guarantees every input fits; a refusal means the two services disagree
            case TextTooLongException tooLong -> "MODEL_RESPONSE_INVALID";
            case IndexProfileMismatchException mismatch -> "INDEX_PROFILE_MISMATCH";
            default -> "INTERNAL_ERROR";
        };
    }
}

package io.groundedaccess.ingestion;

import io.groundedaccess.corpus.IngestionResult;
import io.groundedaccess.corpus.SourceDocument;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for ingestion jobs. Every write made by a worker is conditional on the job still being {@code running} under the attempt that
 * worker claimed, so a worker that lost its lease can no longer change the job.
 */
@Repository
class IngestionJobStore {

    private static final String COLUMNS = """
            id, status, document_count, processed, created, updated, unchanged, chunks, attempts, max_attempts, error_code, created_at, started_at, finished_at
            """;

    private final JdbcClient jdbc;

    IngestionJobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A job held by one worker. {@code attempt} identifies the claim; {@code processed} is where the previous attempt stopped.
     */
    record ClaimedJob(
            UUID id,

            String tenantId,

            int attempt,

            int maxAttempts,

            int documentCount,

            int processed) {
    }

    UUID insert(String tenantId, String submittedBy, List<SourceDocument> documents, int maxAttempts) {
        jdbc.sql("insert into tenant (id, name) values (:id, :id) on conflict (id) do nothing").param("id", tenantId).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into ingestion_job (id, tenant_id, submitted_by, document_count, max_attempts) values (:id, :tenant, :by, :count, :max)")
                .param("id", id)
                .param("tenant", tenantId)
                .param("by", submittedBy)
                .param("count", documents.size())
                .param("max", maxAttempts)
                .update();
        for (int i = 0; i < documents.size(); i++) {
            SourceDocument document = documents.get(i);
            jdbc.sql("""
                            insert into ingestion_job_document (job_id, ordinal, external_key, title, source_uri, content)
                            values (:job, :ordinal, :key, :title, :sourceUri, :content)
                            """)
                    .param("job", id)
                    .param("ordinal", i)
                    .param("key", document.key())
                    .param("title", document.title())
                    .param("sourceUri", document.sourceUri())
                    .param("content", document.content())
                    .update();
        }
        return id;
    }

    Optional<IngestionJob> find(String tenantId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from ingestion_job where id = :id and tenant_id = :tenant")
                .param("id", id)
                .param("tenant", tenantId)
                .query((rs, i) -> job(rs))
                .optional();
    }

    /**
     * Claims the oldest runnable job: a queued job whose retry delay has passed, or a running job whose worker stopped renewing its lease and
     * which still has attempts left. {@code SKIP LOCKED} lets several workers poll at once without claiming the same job.
     */
    Optional<ClaimedJob> claim(Duration lease) {
        return jdbc.sql("""
                        update ingestion_job j
                        set status = 'running', attempts = j.attempts + 1, lease_expires_at = now() + make_interval(secs => :lease),
                            started_at = coalesce(j.started_at, now())
                        where j.id = (
                            select id from ingestion_job
                            where (status = 'queued' and run_after <= now())
                               or (status = 'running' and lease_expires_at < now() and attempts < max_attempts)
                            order by run_after, created_at, id
                            limit 1
                            for update skip locked)
                        returning j.id, j.tenant_id, j.attempts, j.max_attempts, j.document_count, j.processed
                        """)
                .param("lease", seconds(lease))
                .query((rs, i) -> new ClaimedJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)))
                .optional();
    }

    /**
     * Fails running jobs whose lease expired after their last allowed attempt: the worker died and nobody may retry them.
     */
    int failAbandoned() {
        return jdbc.sql("""
                        with lost as (
                            update ingestion_job
                            set status = 'failed', error_code = 'WORKER_LOST', lease_expires_at = null, finished_at = now()
                            where status = 'running' and lease_expires_at < now() and attempts >= max_attempts
                            returning id),
                        dropped as (delete from ingestion_job_document where job_id in (select id from lost))
                        select count(*) from lost
                        """)
                .query(Integer.class)
                .single();
    }

    Optional<SourceDocument> document(UUID jobId, int ordinal) {
        return jdbc.sql("select external_key, title, source_uri, content from ingestion_job_document where job_id = :job and ordinal = :ordinal")
                .param("job", jobId)
                .param("ordinal", ordinal)
                .query((rs, i) -> new SourceDocument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional();
    }

    /**
     * Adds one processed document to the job's counts and renews the lease. Returns false if this worker no longer owns the job.
     */
    boolean recordProgress(ClaimedJob job, IngestionResult result, Duration lease) {
        return jdbc.sql("""
                        update ingestion_job
                        set processed = processed + 1, created = created + :created, updated = updated + :updated, unchanged = unchanged + :unchanged,
                            chunks = chunks + :chunks, lease_expires_at = now() + make_interval(secs => :lease)
                        where id = :id and status = 'running' and attempts = :attempt
                        """)
                .param("created", result.created())
                .param("updated", result.updated())
                .param("unchanged", result.unchanged())
                .param("chunks", result.chunks())
                .param("lease", seconds(lease))
                .param("id", job.id())
                .param("attempt", job.attempt())
                .update() == 1;
    }

    /**
     * Ends the job and drops its submitted documents in one statement; their content now lives in the corpus tables. Returns false if this
     * worker no longer owns the job.
     */
    boolean finish(ClaimedJob job, IngestionJobStatus status, @Nullable String errorCode) {
        return jdbc.sql("""
                        with finished as (
                            update ingestion_job
                            set status = :status, error_code = :error, lease_expires_at = null, finished_at = now()
                            where id = :id and status = 'running' and attempts = :attempt
                            returning id),
                        dropped as (delete from ingestion_job_document where job_id in (select id from finished))
                        select count(*) from finished
                        """)
                .param("status", status.name().toLowerCase(Locale.ROOT))
                .param("error", errorCode)
                .param("id", job.id())
                .param("attempt", job.attempt())
                .query(Integer.class)
                .single() == 1;
    }

    boolean retryLater(ClaimedJob job, String errorCode, Duration delay) {
        return jdbc.sql("""
                        update ingestion_job
                        set status = 'queued', error_code = :error, lease_expires_at = null, run_after = now() + make_interval(secs => :delay)
                        where id = :id and status = 'running' and attempts = :attempt
                        """)
                .param("error", errorCode)
                .param("delay", seconds(delay))
                .param("id", job.id())
                .param("attempt", job.attempt())
                .update() == 1;
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    private static IngestionJob job(ResultSet rs) throws SQLException {
        return new IngestionJob(rs.getObject("id", UUID.class), IngestionJobStatus.fromColumn(rs.getString("status")), rs.getInt("document_count"),
                rs.getInt("processed"), rs.getInt("created"), rs.getInt("updated"), rs.getInt("unchanged"), rs.getInt("chunks"), rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getString("error_code"), instant(rs.getTimestamp("created_at")), nullableInstant(rs.getTimestamp("started_at")),
                nullableInstant(rs.getTimestamp("finished_at")));
    }

    private static Instant instant(@Nullable Timestamp timestamp) {
        if (timestamp == null) {
            throw new IllegalStateException("created_at is not null in the schema");
        }
        return timestamp.toInstant();
    }

    private static @Nullable Instant nullableInstant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

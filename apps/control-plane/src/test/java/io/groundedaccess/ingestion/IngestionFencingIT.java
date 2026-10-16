package io.groundedaccess.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.corpus.DocumentFormat;
import io.groundedaccess.corpus.DocumentScope;
import io.groundedaccess.corpus.SourceDocument;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A worker that lost its claim on a job must not change the corpus. Worker A is held inside the embedding call by a latch while its lease
 * expires; what happens next is decided by the test, not by timing.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.ingestion.worker.max-attempts=3",
        "ga.ingestion.worker.retry-backoff=0s", "ga.ingestion.worker.lease=5m"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IngestionFencingIT.HeldEmbeddings.class})
class IngestionFencingIT {

    private static final String TENANT = "northstar";

    private static final String ADMIN = DemoTokens.token("admin", TENANT, "admin");

    /** When set, the next passage embedding signals {@code entered} and waits for {@code resume}. */
    private static final AtomicReference<Hold> HOLD = new AtomicReference<>();

    record Hold(
            CountDownLatch entered,

            CountDownLatch resume) {
    }

    @TestConfiguration
    static class HeldEmbeddings {

        @Bean
        @Primary
        EmbeddingClient heldEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    Hold hold = inputType == InputType.PASSAGE ? HOLD.getAndSet(null) : null;
                    if (hold != null) {
                        hold.entered().countDown();
                        try {
                            if (!hold.resume().await(60, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("the test never released the held worker");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    return super.embed(texts, inputType);
                }
            };
        }
    }

    @Autowired
    private IngestionWorker worker;

    @Autowired
    private IngestionJobStore store;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MockMvc mvc;

    private final ExecutorService workerA = Executors.newSingleThreadExecutor();

    @BeforeEach
    void reset() {
        jdbc.sql("truncate ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        HOLD.set(null);
    }

    @AfterEach
    void stop() {
        workerA.shutdownNow();
    }

    @Test
    void aWorkerResumingAfterAnotherTookOverItsJobChangesNothing() throws Exception {
        UUID job = submit(document("Old content."), document("New content."));
        Hold hold = holdNextEmbedding();
        Future<Boolean> a = workerA.submit(worker::runOnce);
        awaitEntered(hold);

        expireLease(job);
        worker.runOnce();
        Map<String, Object> afterTakeover = corpus();
        IngestionJob takenOver = store.find(TENANT, job).orElseThrow();
        assertThat(takenOver.status()).isEqualTo(IngestionJobStatus.SUCCEEDED);
        assertThat(afterTakeover).containsEntry("content", "New content.");

        hold.resume().countDown();
        a.get(30, TimeUnit.SECONDS);

        assertThat(corpus()).as("content, version number and labels").isEqualTo(afterTakeover);
        assertThat(store.find(TENANT, job).orElseThrow()).as("status and counts").isEqualTo(takenOver);
    }

    @Test
    void aWorkerWhoseLeaseExpiredDoesNotWriteEvenIfNobodyTookOver() throws Exception {
        UUID job = submit(document("Old content."));
        Hold hold = holdNextEmbedding();
        Future<Boolean> a = workerA.submit(worker::runOnce);
        awaitEntered(hold);

        expireLease(job);
        hold.resume().countDown();
        a.get(30, TimeUnit.SECONDS);

        assertThat(documents()).as("nothing was written").isZero();
        assertThat(store.find(TENANT, job).orElseThrow().processed()).isZero();

        worker.runOnce();
        assertThat(store.find(TENANT, job).orElseThrow()).extracting(IngestionJob::status, IngestionJob::attempts)
                .containsExactly(IngestionJobStatus.SUCCEEDED, 2);
        assertThat(corpus()).containsEntry("content", "Old content.").containsEntry("version_no", 1);
    }

    @Test
    void aWorkerThatLostItsLastAttemptLeavesTheCorpusAsItWas() throws Exception {
        UUID job = submit(document("Old content."));
        jdbc.sql("update ingestion_job set max_attempts = 1 where id = :id").param("id", job).update();
        Hold hold = holdNextEmbedding();
        Future<Boolean> a = workerA.submit(worker::runOnce);
        awaitEntered(hold);

        expireLease(job);
        worker.runOnce();
        hold.resume().countDown();
        a.get(30, TimeUnit.SECONDS);

        assertThat(store.find(TENANT, job).orElseThrow()).extracting(IngestionJob::status, IngestionJob::errorCode)
                .containsExactly(IngestionJobStatus.FAILED, "WORKER_LOST");
        assertThat(documents()).isZero();
    }

    @Test
    void aWorkerResumingAfterTheDocumentWasDeletedDoesNotBringItBack() throws Exception {
        UUID job = submit(document("Old content."));
        Hold hold = holdNextEmbedding();
        Future<Boolean> a = workerA.submit(worker::runOnce);
        awaitEntered(hold);

        expireLease(job);
        worker.runOnce();
        mvc.perform(delete("/api/v1/documents/handbook").header("Authorization", "Bearer " + ADMIN)).andExpect(status().isNoContent());
        hold.resume().countDown();
        a.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.sql("select status from document where external_key = 'handbook'").query(String.class).single()).isEqualTo("deleted");
        assertThat(jdbc.sql("select last_version_no from document where external_key = 'handbook'").query(Integer.class).single()).isEqualTo(1);
    }

    private Hold holdNextEmbedding() {
        Hold hold = new Hold(new CountDownLatch(1), new CountDownLatch(1));
        HOLD.set(hold);
        return hold;
    }

    private static void awaitEntered(Hold hold) throws InterruptedException {
        assertThat(hold.entered().await(30, TimeUnit.SECONDS)).as("worker A reached the embedding call").isTrue();
    }

    private void expireLease(UUID job) {
        jdbc.sql("update ingestion_job set lease_expires_at = now() - interval '1 second' where id = :id").param("id", job).update();
    }

    private UUID submit(SourceDocument... documents) {
        return transactions.execute(status -> store.insert(TENANT, "admin", List.of(documents), 3));
    }

    private static SourceDocument document(String content) {
        return new SourceDocument("handbook", "Handbook", null, content, DocumentFormat.TEXT, AccessLabels.UNRESTRICTED, DocumentScope.EVERYWHERE_ALWAYS);
    }

    private int documents() {
        return jdbc.sql("select count(*) from document").query(Integer.class).single();
    }

    private Map<String, Object> corpus() {
        return jdbc.sql("""
                        select c.content, v.version_no, d.last_version_no, d.status, v.classification
                        from document d join document_version v on v.id = d.active_version_id join chunk c on c.version_id = v.id
                        where d.external_key = 'handbook'
                        """)
                .query()
                .singleRow();
    }
}

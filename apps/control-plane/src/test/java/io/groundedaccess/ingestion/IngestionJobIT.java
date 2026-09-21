package io.groundedaccess.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.corpus.IngestionResult;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.ingestion.worker.max-attempts=3", "ga.ingestion.worker.retry-backoff=0s"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IngestionJobIT.FakeModels.class})
class IngestionJobIT {

    /** Embedding calls for text containing {@link #FLAKY} fail while this is positive, as if the model service were down. */
    private static final AtomicInteger OUTAGES = new AtomicInteger();

    private static final String FLAKY = "flaky";

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient flakyEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    if (texts.stream().anyMatch(t -> t.contains(FLAKY)) && OUTAGES.getAndDecrement() > 0) {
                        throw new ResourceAccessException("model service unavailable");
                    }
                    return super.embed(texts, inputType);
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionWorker worker;

    @Autowired
    private IngestionJobStore store;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        jdbc.sql("truncate ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        OUTAGES.set(0);
    }

    @Test
    void acceptsAJobAtOnceAndReportsTheResultAfterTheWorkerRunsIt() throws Exception {
        String location = submit(ADMIN, "hr-travel-policy", "hr-volunteer-policy")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/api/v1/ingestion-jobs/")))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.documents").value(2))
                .andExpect(jsonPath("$.processed").value(0))
                .andReturn().getResponse().getHeader("Location");

        assertThat(worker.drain()).isEqualTo(1);

        job(location).andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.processed").value(2))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.chunks").value(2))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.finishedAt").exists());
        assertThat(storedDocuments()).isZero();
    }

    @Test
    void showsAJobOnlyToAdminsOfItsTenant() throws Exception {
        String location = submit(ADMIN, "hr-travel-policy").andReturn().getResponse().getHeader("Location");

        job(location).andExpect(status().isOk());
        mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + DemoTokens.token("admin", "external", "admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/ingestion-jobs/" + UUID.randomUUID()).header("Authorization", "Bearer " + ADMIN)).andExpect(status().isNotFound());
        mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + DemoTokens.token("alice", "northstar", "query")))
                .andExpect(status().isForbidden());
    }

    @Test
    void retriesAModelServiceOutageAndResumesAfterTheLastRecordedDocument() throws Exception {
        OUTAGES.set(1);
        String location = submit(ADMIN, "hr-travel-policy", FLAKY + "-policy", "hr-volunteer-policy").andReturn().getResponse().getHeader("Location");

        worker.drain();

        // Without resuming, the second attempt would count the first document as unchanged instead of created.
        job(location).andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.unchanged").value(0))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    void failsAfterTheLastAttemptWithAnErrorCodeAndKeepsThePartialCounts() throws Exception {
        OUTAGES.set(Integer.MAX_VALUE);
        String location = submit(ADMIN, "hr-travel-policy", FLAKY + "-policy").andReturn().getResponse().getHeader("Location");

        worker.drain();

        job(location).andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.attempts").value(3))
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errorCode").value("MODEL_SERVICE_UNAVAILABLE"));
        assertThat(storedDocuments()).isZero();
    }

    @Test
    void takesOverAJobWhoseWorkerStoppedRenewingItsLease() throws Exception {
        String location = submit(ADMIN, "hr-travel-policy").andReturn().getResponse().getHeader("Location");
        jdbc.sql("update ingestion_job set status = 'running', attempts = 1, lease_expires_at = now() - interval '1 second'").update();

        worker.drain();

        job(location).andExpect(jsonPath("$.status").value("succeeded")).andExpect(jsonPath("$.attempts").value(2));
    }

    @Test
    void failsAJobWhoseWorkerStoppedDuringTheLastAttempt() throws Exception {
        String location = submit(ADMIN, "hr-travel-policy").andReturn().getResponse().getHeader("Location");
        jdbc.sql("update ingestion_job set status = 'running', attempts = max_attempts, lease_expires_at = now() - interval '1 second'").update();

        worker.drain();

        job(location).andExpect(jsonPath("$.status").value("failed")).andExpect(jsonPath("$.errorCode").value("WORKER_LOST"));
        assertThat(storedDocuments()).isZero();
    }

    @Test
    void aWorkerThatLostItsClaimCannotChangeTheJob() throws Exception {
        submit(ADMIN, "hr-travel-policy");
        IngestionJobStore.ClaimedJob claim = store.claim(Duration.ofMinutes(5)).orElseThrow();
        jdbc.sql("update ingestion_job set attempts = attempts + 1").update();

        assertThat(store.recordProgress(claim, new IngestionResult(1, 0, 0, 1), Duration.ofMinutes(5))).isFalse();
        assertThat(store.finish(claim, IngestionJobStatus.SUCCEEDED, null)).isFalse();
        assertThat(store.retryLater(claim, "MODEL_SERVICE_UNAVAILABLE", Duration.ZERO)).isFalse();
    }

    @Test
    void concurrentWorkersClaimEveryJobExactlyOnce() throws Exception {
        for (int i = 0; i < 20; i++) {
            submit(ADMIN, "doc-" + i);
        }
        List<UUID> claimed = Collections.synchronizedList(new ArrayList<>());
        Callable<Void> poller = () -> {
            Optional<IngestionJobStore.ClaimedJob> job;
            while ((job = store.claim(Duration.ofMinutes(5))).isPresent()) {
                claimed.add(job.get().id());
            }
            return null;
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            pool.invokeAll(List.of(poller, poller, poller, poller)).forEach(f -> assertThat(f).succeedsWithin(Duration.ofSeconds(30)));
        }

        assertThat(claimed).hasSize(20).doesNotHaveDuplicates();
    }

    @Test
    void aClaimSkipsJobsLockedByAnotherTransactionInsteadOfWaiting() throws Exception {
        UUID oldest = jobId(submit(ADMIN, "hr-travel-policy"));
        UUID next = jobId(submit(ADMIN, "hr-volunteer-policy"));

        // The pool is not closed inside the transaction: a claim that waits for the lock would then wait forever for this transaction to end.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        UUID claimed;
        try {
            claimed = transactions.execute(status -> {
                jdbc.sql("select id from ingestion_job where id = :id for update").param("id", oldest).query(UUID.class).single();
                try {
                    return pool.submit(() -> store.claim(Duration.ofMinutes(5)).orElseThrow().id()).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the claim waited for the locked job", e);
                }
            });
        } finally {
            pool.shutdown();
        }

        assertThat(claimed).isEqualTo(next);
    }

    private UUID jobId(ResultActions submitted) throws Exception {
        return UUID.fromString(JsonPath.read(submitted.andReturn().getResponse().getContentAsString(), "$.jobId"));
    }

    private ResultActions submit(String token, String... keys) throws Exception {
        StringBuilder documents = new StringBuilder();
        for (String key : keys) {
            documents.append(documents.isEmpty() ? "" : ",")
                    .append("{\"key\":\"%s\",\"title\":\"%s\",\"content\":\"# %s\\n\\nThe %s document body.\\n\"}".formatted(key, key, key, key));
        }
        return mvc.perform(post("/api/v1/ingestion-jobs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documents\":[" + documents + "]}"));
    }

    private ResultActions job(String location) throws Exception {
        return mvc.perform(get(location).header("Authorization", "Bearer " + ADMIN)).andExpect(status().isOk());
    }

    private int storedDocuments() {
        return jdbc.sql("select count(*) from ingestion_job_document").query(Integer.class).single();
    }
}

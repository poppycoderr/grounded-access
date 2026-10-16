package io.groundedaccess.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.corpus.DocumentFormat;
import io.groundedaccess.corpus.DocumentScope;
import io.groundedaccess.corpus.SourceDocument;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A worker that is alive keeps its job while one document takes longer than the lease: the lease is renewed in the background.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.ingestion.worker.lease=2s"})
@Import({TestcontainersConfiguration.class, IngestionLeaseRenewalIT.SlowEmbeddings.class})
class IngestionLeaseRenewalIT {

    private static final CountDownLatch ENTERED = new CountDownLatch(1);

    private static final CountDownLatch RESUME = new CountDownLatch(1);

    @TestConfiguration
    static class SlowEmbeddings {

        @Bean
        @Primary
        EmbeddingClient slowEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    ENTERED.countDown();
                    try {
                        RESUME.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
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

    @Test
    void theLeaseOutlivesItsOwnLengthWhileTheWorkerIsBusy() throws Exception {
        var document = new SourceDocument("handbook", "Handbook", null, "Some text.", DocumentFormat.TEXT, AccessLabels.UNRESTRICTED,
                DocumentScope.EVERYWHERE_ALWAYS);
        UUID job = transactions.execute(status -> store.insert("northstar", "admin", List.of(document), 3));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Boolean> running = pool.submit(worker::runOnce);
            assertThat(ENTERED.await(30, TimeUnit.SECONDS)).isTrue();

            for (int check = 0; check < 3; check++) {
                Thread.sleep(1500);
                assertThat(jdbc.sql("select lease_expires_at > now() from ingestion_job where id = :id").param("id", job).query(Boolean.class).single())
                        .as("lease still valid after %d ms of a 2 s lease", (check + 1) * 1500)
                        .isTrue();
            }
            assertThat(store.claim(Duration.ofMinutes(5))).as("nobody can take over a job whose worker is alive").isEmpty();

            RESUME.countDown();
            running.get(30, TimeUnit.SECONDS);
        }

        assertThat(store.find("northstar", job).orElseThrow()).extracting(IngestionJob::status, IngestionJob::attempts, IngestionJob::created)
                .containsExactly(IngestionJobStatus.SUCCEEDED, 1, 1);
    }
}

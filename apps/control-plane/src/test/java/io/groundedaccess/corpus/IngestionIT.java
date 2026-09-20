package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import({TestcontainersConfiguration.class, IngestionIT.FakeModels.class})
class IngestionIT {

    private static final String TENANT = "northstar";

    private static final String KEY = "hr-volunteer-policy";

    /** Both ingestions meet here before they embed, so they reach the write transaction at the same time and really race. */
    private static final CyclicBarrier RACE = new CyclicBarrier(2);

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient racingEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    try {
                        RACE.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return super.embed(texts, inputType);
                }
            };
        }
    }

    @Autowired
    private IngestionService ingestion;

    @Autowired
    private EmbeddingModelGuard guard;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetCorpus() {
        jdbc.sql("truncate chunk, document_version, document, tenant cascade").update();
        RACE.reset();
    }

    @Test
    void concurrentIngestionOfTheSameContentWritesExactlyOneVersion() throws Exception {
        List<IngestionResult> results = inParallel(document("EU employees receive two paid volunteer days."),
                document("EU employees receive two paid volunteer days."));

        assertThat(results).extracting(IngestionResult::created).containsExactlyInAnyOrder(1, 0);
        assertThat(results).extracting(IngestionResult::unchanged).containsExactlyInAnyOrder(0, 1);
        assertThat(versionNumbers()).containsExactly(1);
        assertThat(chunkVersions()).hasSize(1);
    }

    @Test
    void concurrentIngestionOfDifferentContentWritesTwoOrderedVersions() throws Exception {
        List<IngestionResult> results = inParallel(document("EU employees receive two paid volunteer days."),
                document("EU employees receive three paid volunteer days."));

        assertThat(results).extracting(IngestionResult::created).containsExactlyInAnyOrder(1, 0);
        assertThat(results).extracting(IngestionResult::updated).containsExactlyInAnyOrder(0, 1);
        assertThat(versionNumbers()).containsExactly(1, 2);
        assertThat(chunkVersions()).hasSize(1);
    }

    @Test
    void concurrentUpdatesOfAnExistingDocumentAppendVersionsInOrder() throws Exception {
        RACE.reset();
        ingestSequentially(document("EU employees receive two paid volunteer days."));

        List<IngestionResult> results = inParallel(document("EU employees receive three paid volunteer days."),
                document("EU employees receive four paid volunteer days."));

        assertThat(results).extracting(IngestionResult::updated).containsExactly(1, 1);
        assertThat(versionNumbers()).containsExactly(1, 2, 3);
        assertThat(chunkVersions()).hasSize(1);
    }

    @Test
    void refusesToStartWhenActiveDocumentsUseAnotherEmbeddingModel() throws Exception {
        inParallel(document("EU employees receive two paid volunteer days."), document("EU employees receive two paid volunteer days."));
        jdbc.sql("update document_version set embedding_model = 'other-model@rev'").update();

        assertThat(catchThrowable(guard::check)).hasMessageContaining("other-model@rev").hasMessageContaining("Re-ingest");
    }

    private static Throwable catchThrowable(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }

    private List<IngestionResult> inParallel(SourceDocument first, SourceDocument second) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Callable<IngestionResult>> calls = List.of(() -> ingestion.ingest(TENANT, List.of(first)), () -> ingestion.ingest(TENANT, List.of(second)));
            List<Future<IngestionResult>> futures = pool.invokeAll(calls);
            return List.of(futures.get(0).get(), futures.get(1).get());
        }
    }

    private void ingestSequentially(SourceDocument document) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<IngestionResult> ingest = pool.submit(() -> ingestion.ingest(TENANT, List.of(document)));
            pool.submit(() -> {
                RACE.await();
                return null;
            });
            ingest.get();
        }
        RACE.reset();
    }

    private static SourceDocument document(String body) {
        return new SourceDocument(KEY, "Volunteer Time Off Policy", null, "# Volunteer Time Off Policy\n\n" + body + "\n");
    }

    private List<Integer> versionNumbers() {
        return jdbc.sql("select version_no from document_version order by version_no").query(Integer.class).list();
    }

    private List<String> chunkVersions() {
        return jdbc.sql("select distinct version_id::text from chunk c join document d on d.active_version_id = c.version_id").query(String.class).list();
    }
}

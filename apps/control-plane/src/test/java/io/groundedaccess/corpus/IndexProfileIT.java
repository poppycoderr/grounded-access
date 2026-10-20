package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.authorization.Classification;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.web.client.ResourceAccessException;

/**
 * The index profile (ADR-0007): vectors of two profiles are never compared or mixed, a model service that changes is noticed on its next
 * answer, and a stored profile this process cannot produce stops it at startup unless only the model service is missing.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IndexProfileIT.Models.class})
class IndexProfileIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String READER = DemoTokens.token("reader", "northstar", "query");

    /** What the stand-in model service reports; the defaults are what the hashing client reports. */
    private static final AtomicReference<String> REVISION = new AtomicReference<>("test");

    private static final AtomicReference<String> QUERY_PREFIX = new AtomicReference<>("");

    private static final AtomicInteger DIMENSIONS = new AtomicInteger(384);

    private static final AtomicBoolean DOWN = new AtomicBoolean();

    @TestConfiguration
    static class Models {

        @Bean
        @Primary
        EmbeddingClient switchableEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    if (DOWN.get()) {
                        throw new ResourceAccessException("model service unavailable");
                    }
                    Embeddings hashed = super.embed(texts, inputType);
                    return new Embeddings(hashed.model(), REVISION.get(), inputType == InputType.QUERY ? QUERY_PREFIX.get() : "", DIMENSIONS.get(),
                            hashed.vectors());
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionService ingestion;

    @Autowired
    private IngestionWorker worker;

    @Autowired
    private IndexProfiles profiles;

    @Autowired
    private EmbeddingClient embeddings;

    @BeforeEach
    void reset() {
        jdbc.sql("truncate ingestion_job_document, ingestion_job, chunk, document_version, document, tenant, query_execution cascade").update();
        REVISION.set("test");
        QUERY_PREFIX.set("");
        DIMENSIONS.set(384);
        DOWN.set(false);
    }

    @Test
    void recordsTheProfileWithEveryVersionAndEverySearch() throws Exception {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));

        UUID profile = profiles.establish().id();
        assertThat(jdbc.sql("select index_profile_id from document_version").query(UUID.class).list()).containsExactly(profile);
        String execution = JsonPath.read(search("volunteer days", "hybrid-rrf").andExpect(jsonPath("$.degraded", empty()))
                .andReturn()
                .getResponse()
                .getContentAsString(), "$.executionId");
        assertThat(jdbc.sql("select index_profile_id from query_execution where id = :id").param("id", UUID.fromString(execution)).query(UUID.class).single())
                .isEqualTo(profile);
    }

    @Test
    void aQueryEmbeddedWithAnotherProfileIsNeverComparedWithStoredVectors() throws Exception {
        for (Runnable change : List.<Runnable>of(() -> REVISION.set("rebuilt"), () -> QUERY_PREFIX.set("query: "), () -> DIMENSIONS.set(768))) {
            reset();
            ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));
            change.run();

            String hybrid = search("volunteer days", "hybrid-rrf").andExpect(status().isOk())
                    .andExpect(jsonPath("$.degraded", contains("dense_index_mismatch")))
                    .andExpect(jsonPath("$.results[*].documentKey", contains("handbook")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(jdbc.sql("select index_profile_id is null from query_execution where id = :id")
                    .param("id", UUID.fromString(JsonPath.read(hybrid, "$.executionId")))
                    .query(Boolean.class)
                    .single()).isTrue();
            search("volunteer days", "dense-only").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INDEX_PROFILE_MISMATCH"));
        }
    }

    @Test
    void denseSearchReadsOnlyRowsOfTheQueryProfile() throws Exception {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days."),
                document("guide", "Employees receive two paid volunteer days.")));
        UUID other = profileRow("hashing", "older", "");
        jdbc.sql("update document_version v set index_profile_id = :other from document d where d.id = v.document_id and d.external_key = 'guide'")
                .param("other", other)
                .update();

        search("volunteer days", "dense-only").andExpect(jsonPath("$.results[*].documentKey", contains("handbook")));
        search("volunteer days", "sparse-only").andExpect(jsonPath("$.results[*].documentKey", hasItem("guide")));
    }

    @Test
    void ingestionWithAnotherProfileFailsBeforeWritingAndIsNotRetried() throws Exception {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));
        REVISION.set("rebuilt");

        assertThatThrownBy(() -> ingestion.ingest("northstar", List.of(document("handbook", "Employees receive three paid volunteer days."))))
                .isInstanceOf(IndexProfileMismatchException.class)
                .hasMessageContaining("hashing@rebuilt");
        String location = mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"documents":[{"key":"handbook","title":"Handbook","content":"Employees receive four paid volunteer days.","format":"text"}]}
                        """)).andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
        worker.drain();

        mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + ADMIN))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.errorCode").value("INDEX_PROFILE_MISMATCH"))
                .andExpect(jsonPath("$.attempts").value(1));
        assertThat(jdbc.sql("select count(*) from document_version").query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("select content from chunk").query(String.class).list()).containsExactly("Employees receive two paid volunteer days.");
    }

    @Test
    void aLabelChangeKeepsTheProfileOfTheVersionItReplacesWithoutTheModelService() {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));
        DOWN.set(true);

        ingestion.ingest("northstar", List.of(new SourceDocument("handbook", "Handbook", null, "Employees receive two paid volunteer days.", DocumentFormat.TEXT,
                new AccessLabels(Classification.INTERNAL, Set.of(), Set.of()),
                DocumentScope.EVERYWHERE_ALWAYS)));

        assertThat(jdbc.sql("select distinct index_profile_id from document_version").query(UUID.class).list()).containsExactly(profiles.establish().id());
    }

    @Test
    void startsWhenTheStoredProfileIsTheOneThisProcessProduces() {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));

        assertThatCode(() -> fresh(180, 30, true).checkAtStartup()).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWhenTheModelServiceProducesAnotherProfile() {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));

        REVISION.set("rebuilt");
        assertThatThrownBy(() -> fresh(180, 30, true).checkAtStartup()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hashing@test")
                .hasMessageContaining("hashing@rebuilt")
                .hasMessageContaining("load the corpus into an empty database");
        REVISION.set("test");
        QUERY_PREFIX.set("query: ");
        assertThatThrownBy(() -> fresh(180, 30, true).checkAtStartup()).hasMessageContaining("query prefix \"query: \"");
        QUERY_PREFIX.set("");
        DIMENSIONS.set(768);
        assertThatThrownBy(() -> fresh(180, 30, true).checkAtStartup()).hasMessageContaining("768 dimensions; the vector column has 384");
    }

    @Test
    void refusesToStartWhenTheConfigurationCannotProduceTheStoredProfileEvenWithTheModelServiceDown() {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));
        DOWN.set(true);

        assertThatThrownBy(() -> fresh(180, 30, false).checkAtStartup()).hasMessageContaining("embedding context on, chunker release 4");
        assertThatThrownBy(() -> fresh(200, 30, true).checkAtStartup()).hasMessageContaining("180/30 words");
        assertThatThrownBy(() -> fresh(180, 20, true).checkAtStartup()).hasMessageContaining("180/30 words");
    }

    @Test
    void startsWithoutTheModelServiceAndEstablishesTheProfileWhenItAnswers() {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));
        DOWN.set(true);
        IndexProfiles started = fresh(180, 30, true);

        assertThatCode(started::checkAtStartup).doesNotThrowAnyException();
        assertThatThrownBy(started::establish).isInstanceOf(ResourceAccessException.class);
        DOWN.set(false);
        assertThat(started.establish().id()).isEqualTo(profiles.establish().id());
    }

    @Test
    void refusesToStartOnVersionsWhoseRevisionWasNeverRecorded() {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));
        UUID unknown = profileRow("hashing", "unknown", "");
        jdbc.sql("update document_version set index_profile_id = :unknown").param("unknown", unknown).update();

        assertThatThrownBy(() -> fresh(180, 30, true).checkAtStartup()).hasMessageContaining("hashing@unknown");
    }

    private IndexProfiles fresh(int maxWords, int overlapWords, boolean embeddingContext) {
        return new IndexProfiles(jdbc, embeddings, maxWords, overlapWords, embeddingContext);
    }

    private UUID profileRow(String model, String revision, String queryPrefix) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into index_profile (id, embedding_model, embedding_revision, dimensions, query_prefix, passage_prefix, embedding_context,
                    chunker_release, chunk_max_words, chunk_overlap_words, chunk_max_tokens, chunk_max_chars, heading_max_chars)
                values (:id, :model, :revision, 384, :prefix, '', true, '4', 180, 30, 384, 2000, 200)
                on conflict do nothing
                """).param("id", id).param("model", model).param("revision", revision).param("prefix", queryPrefix).update();
        return jdbc.sql("select id from index_profile where embedding_model = :model and embedding_revision = :revision and query_prefix = :prefix")
                .param("model", model)
                .param("revision", revision)
                .param("prefix", queryPrefix)
                .query(UUID.class)
                .single();
    }

    private ResultActions search(String query, String strategy) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"strategy\":\"%s\"}".formatted(query, strategy)));
    }

    private static SourceDocument document(String key, String content) {
        return new SourceDocument(key, "Handbook", null, content, DocumentFormat.TEXT, AccessLabels.UNRESTRICTED, DocumentScope.EVERYWHERE_ALWAYS);
    }
}

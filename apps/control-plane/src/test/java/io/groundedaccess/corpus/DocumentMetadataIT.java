package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.web.client.ResourceAccessException;

/**
 * Which submissions count as a change of a document's metadata, and what a change does: labels, scope, title and source link are compared
 * field by field, and a metadata-only change creates a version without calling the model service.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.ingestion.worker.max-attempts=1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DocumentMetadataIT.FakeModels.class})
class DocumentMetadataIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final AtomicInteger PASSAGE_EMBEDDINGS = new AtomicInteger();

    private static final AtomicBoolean MODEL_SERVICE_DOWN = new AtomicBoolean();

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient countingEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    if (inputType == InputType.PASSAGE) {
                        if (MODEL_SERVICE_DOWN.get()) {
                            throw new ResourceAccessException("model service unavailable");
                        }
                        PASSAGE_EMBEDDINGS.incrementAndGet();
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

    @BeforeEach
    void reset() {
        jdbc.sql("truncate ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        PASSAGE_EMBEDDINGS.set(0);
        MODEL_SERVICE_DOWN.set(false);
    }

    @Test
    void aLabelValueContainingAControlCharacterIsRejectedAndTheLabelsStay() throws Exception {
        ingest(document("handbook", "Handbook", null, "\"allowedDepartments\":[\"engineering\",\"finance\"]")).andExpect(jsonPath("$.created").value(1));

        submit(document("handbook", "Handbook", null, "\"allowedDepartments\":[\"engineering\\u001Ffinance\"]")).andExpect(status().isBadRequest());
        submit(document("handbook", "Handbook", null, "\"appliesToRegions\":[\"EU\\nUS\"]")).andExpect(status().isBadRequest());

        assertThat(readableBy("engineering")).isTrue();
    }

    @Test
    void narrowingTheDepartmentsHidesTheDocumentFromEveryReadPathWhileTheModelServiceIsDown() throws Exception {
        ingest(document("handbook", "Handbook", null, "\"allowedDepartments\":[\"engineering\",\"finance\"]"));
        assertThat(readableBy("engineering")).isTrue();
        MODEL_SERVICE_DOWN.set(true);

        ingest(document("handbook", "Handbook", null, "\"allowedDepartments\":[\"finance\"]")).andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.chunks").value(0));

        assertThat(readableBy("engineering")).isFalse();
        assertThat(readableBy("finance")).isTrue();
    }

    @Test
    void theSameLabelsInAnotherOrderAreUnchanged() throws Exception {
        ingest(document("handbook", "Handbook", null, "\"allowedDepartments\":[\"engineering\",\"finance\"],\"appliesToRegions\":[\"EU\",\"US\"]"));

        ingest(document("handbook", "Handbook", null, "\"allowedDepartments\":[\"finance\",\"engineering\"],\"appliesToRegions\":[\"US\",\"EU\"]"))
                .andExpect(jsonPath("$.unchanged").value(1));
        assertThat(PASSAGE_EMBEDDINGS).hasValue(1);
    }

    @Test
    void aNewTitleOrSourceLinkIsANewVersionThatKeepsTheEmbeddings() throws Exception {
        ingest(document("handbook", "Handbook", "https://wiki.example/handbook", "\"classification\":\"public\""));
        List<String> chunks = chunkIds();

        ingest(document("handbook", "Employee Handbook", "https://wiki.example/handbook", "\"classification\":\"public\"")).andExpect(
                jsonPath("$.updated").value(1));
        assertThat(current()).containsEntry("title", "Employee Handbook").containsEntry("source_uri", "https://wiki.example/handbook")
                .containsEntry("version_no", 2);

        ingest(document("handbook", "Employee Handbook", "https://wiki.example/people/handbook", "\"classification\":\"public\"")).andExpect(
                jsonPath("$.updated").value(1));
        assertThat(current()).containsEntry("source_uri", "https://wiki.example/people/handbook").containsEntry("version_no", 3);

        ingest(document("handbook", "Employee Handbook", null, "\"classification\":\"public\"")).andExpect(jsonPath("$.updated").value(1));
        assertThat(current()).containsEntry("source_uri", null).containsEntry("version_no", 4);

        ingest(document("handbook", "Employee Handbook", null, "\"classification\":\"public\"")).andExpect(jsonPath("$.unchanged").value(1));

        assertThat(PASSAGE_EMBEDDINGS).as("only the first submission embedded anything").hasValue(1);
        assertThat(chunkIds()).as("the chunks moved to each new version").isEqualTo(chunks);
        mvc.perform(get("/api/v1/documents/handbook").header("Authorization", "Bearer " + reader(Map.of())))
                .andExpect(jsonPath("$.title").value("Employee Handbook"))
                .andExpect(jsonPath("$.versionNo").value(4));
    }

    @Test
    void anEmptySourceLinkMeansNoSourceLink() throws Exception {
        ingest(document("handbook", "Handbook", null, "\"classification\":\"public\""));

        ingest(document("handbook", "Handbook", "", "\"classification\":\"public\"")).andExpect(jsonPath("$.unchanged").value(1));
        assertThat(current()).containsEntry("source_uri", null);
    }

    private Map<String, Object> current() {
        return jdbc.sql("""
                        select v.title, v.source_uri, v.version_no from document d join document_version v on v.id = d.active_version_id
                        where d.external_key = 'handbook'
                        """)
                .query()
                .singleRow();
    }

    private List<String> chunkIds() {
        return jdbc.sql("select c.id::text from chunk c join document d on d.active_version_id = c.version_id order by c.ordinal").query(String.class).list();
    }

    /**
     * Whether a principal of the given department reaches the document through the listing, a search and a document read; all three must agree.
     */
    private boolean readableBy(String department) throws Exception {
        String token = reader(Map.of("department", department));
        String listing = mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
        boolean listed = !JsonPath.<List<String>>read(listing, "$.chunks[*].documentKey").isEmpty();
        String search = mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"company handbook policy\",\"strategy\":\"sparse-only\"}")).andReturn().getResponse().getContentAsString();
        boolean found = !JsonPath.<List<String>>read(search, "$.results[*].documentKey").isEmpty();
        boolean read = mvc.perform(get("/api/v1/documents/handbook").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus() == 200;
        assertThat(found).as("search agrees with the listing").isEqualTo(listed);
        assertThat(read).as("the document read agrees with the listing").isEqualTo(listed);
        return listed;
    }

    private static String reader(Map<String, Object> claims) {
        Map<String, Object> all = new java.util.HashMap<>(Map.of("sub", "reader", "tenant_id", "northstar", "scope", "query debug", "clearance", "internal"));
        all.putAll(claims);
        return DemoTokens.sign(all, "grounded-access-demo");
    }

    private static String document(String key, String title, String sourceUri, String metadata) {
        String uri = sourceUri == null ? "" : ",\"sourceUri\":\"%s\"".formatted(sourceUri);
        return "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\"%s,\"content\":\"# Handbook\\n\\nThe company handbook policy.\\n\",%s}]}".formatted(key, title, uri,
                metadata);
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ingest(String body) throws Exception {
        String location = submit(body).andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
        worker.drain();
        return mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$.status").value("succeeded"));
    }
}

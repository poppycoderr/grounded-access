package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.EmbeddingClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DocumentLifecycleIT.FakeModels.class})
class DocumentLifecycleIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String READER = DemoTokens.token("eval", "northstar", "query debug");

    private static final String TRAVEL = "# Travel\n\n## Meals\n\nThe meal allowance is 60 EUR per day.\n";

    private static final String VOLUNTEER = "# Volunteer\n\n## European Union\n\nEU employees receive two paid volunteer days.\n";

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionWorker worker;

    @Autowired
    private CorpusCleanup cleanup;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        jdbc.sql("truncate ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
    }

    @Test
    void aDisabledDocumentLeavesBothChannelsAndTheListingOnTheNextQueryAndReturnsWhenEnabled() throws Exception {
        ingest("hr-travel-policy", TRAVEL);
        ingest("hr-volunteer-policy", VOLUNTEER);

        changeStatus("hr-travel-policy", "disabled").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.versionNo").value(1));

        assertThat(visibleKeys()).containsExactly("hr-volunteer-policy");
        for (String strategy : new String[] {"sparse-only", "dense-only"}) {
            search("meal allowance per day", strategy).andExpect(jsonPath("$.results[*].documentKey", contains("hr-volunteer-policy")));
        }

        changeStatus("hr-travel-policy", "active").andExpect(jsonPath("$.status").value("active"));
        assertThat(visibleKeys()).containsExactly("hr-travel-policy", "hr-volunteer-policy");
    }

    @Test
    void newContentDoesNotReEnableADisabledDocument() throws Exception {
        ingest("hr-travel-policy", TRAVEL);
        changeStatus("hr-travel-policy", "disabled");

        ingest("hr-travel-policy", TRAVEL.replace("60", "75")).andExpect(jsonPath("$.updated").value(1));

        assertThat(visibleKeys()).isEmpty();
        changeStatus("hr-travel-policy", "active").andExpect(jsonPath("$.versionNo").value(2));
        search("meal allowance", "sparse-only").andExpect(jsonPath("$.results[0].text", startsWith("The meal allowance is 75 EUR")));
    }

    @Test
    void aDeletedDocumentDisappearsAtOnceAndIsThenUnknown() throws Exception {
        ingest("hr-travel-policy", TRAVEL);

        mvc.perform(delete("/api/v1/documents/hr-travel-policy").header("Authorization", "Bearer " + ADMIN)).andExpect(status().isNoContent());

        assertThat(visibleKeys()).isEmpty();
        search("meal allowance", "dense-only").andExpect(jsonPath("$.results", empty()));
        mvc.perform(delete("/api/v1/documents/hr-travel-policy").header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        changeStatus("hr-travel-policy", "active").andExpect(status().isNotFound());
    }

    @Test
    void documentsOfAnotherTenantOrWithoutTheAdminScopeCannotBeChanged() throws Exception {
        ingest("hr-travel-policy", TRAVEL);
        String outsider = DemoTokens.token("admin", "external", "admin");

        mvc.perform(delete("/api/v1/documents/hr-travel-policy").header("Authorization", "Bearer " + outsider)).andExpect(status().isNotFound());
        mvc.perform(statusChange(outsider, "hr-travel-policy", "disabled")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/hr-travel-policy").header("Authorization", "Bearer " + READER)).andExpect(status().isForbidden());
        mvc.perform(statusChange(ADMIN, "hr-travel-policy", "deleted")).andExpect(status().isBadRequest());

        assertThat(visibleKeys()).containsExactly("hr-travel-policy");
    }

    @Test
    void ingestingADeletedKeyAgainCreatesItAfreshEvenWithTheSameContent() throws Exception {
        ingest("hr-travel-policy", TRAVEL);
        mvc.perform(delete("/api/v1/documents/hr-travel-policy").header("Authorization", "Bearer " + ADMIN));

        ingest("hr-travel-policy", TRAVEL).andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.unchanged").value(0));

        assertThat(visibleKeys()).containsExactly("hr-travel-policy");
    }

    @Test
    void cleanupRemovesReplacedVersionsAndDeletedDocumentsButNotWhatQueriesReach() throws Exception {
        ingest("hr-travel-policy", TRAVEL);
        ingest("hr-travel-policy", TRAVEL.replace("60", "75"));
        ingest("hr-volunteer-policy", VOLUNTEER);
        ingest("eng-oncall", "# On-call\n\nPages are acknowledged within five minutes.\n");
        changeStatus("eng-oncall", "disabled");
        mvc.perform(delete("/api/v1/documents/hr-volunteer-policy").header("Authorization", "Bearer " + ADMIN));
        List<String> before = visibleChunkIds();

        assertThat(cleanup.purge()).isEqualTo(2);

        assertThat(versions()).containsExactlyInAnyOrder("eng-oncall:1", "hr-travel-policy:2");
        assertThat(chunkVersions()).containsExactlyInAnyOrder("eng-oncall:1", "hr-travel-policy:2");
        assertThat(visibleChunkIds()).isEqualTo(before);
        assertThat(cleanup.purge()).isZero();

        ingest("hr-volunteer-policy", VOLUNTEER).andExpect(jsonPath("$.created").value(1));
        assertThat(visibleKeys()).containsExactly("hr-travel-policy", "hr-volunteer-policy");
    }

    @Test
    void cleanupSkipsADocumentAnIngestionHoldsInsteadOfWaiting() throws Exception {
        ingest("hr-travel-policy", TRAVEL);
        ingest("hr-travel-policy", TRAVEL.replace("60", "75"));

        // The pool is not closed inside the transaction: a cleanup that waits for the lock would then wait forever for this transaction to end.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        int removed;
        try {
            removed = transactions.execute(status -> {
                jdbc.sql("select id from document where external_key = 'hr-travel-policy' for no key update").query(UUID.class).single();
                try {
                    return pool.submit(cleanup::purge).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("cleanup waited for the locked document", e);
                }
            });
        } finally {
            pool.shutdown();
        }

        assertThat(removed).isZero();
        assertThat(cleanup.purge()).isEqualTo(1);
    }

    private ResultActions ingest(String key, String content) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\",\"content\":%s}]}".formatted(key, key, quote(content));
        String location = mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        worker.drain();
        return mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$.status").value("succeeded"));
    }

    private ResultActions changeStatus(String key, String status) throws Exception {
        return mvc.perform(statusChange(ADMIN, key, status));
    }

    private static RequestBuilder statusChange(String token, String key, String status) {
        return patch("/api/v1/documents/" + key).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\"}".formatted(status));
    }

    private ResultActions search(String query, String strategy) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":%s,\"strategy\":\"%s\"}".formatted(quote(query), strategy))).andExpect(status().isOk());
    }

    private List<String> visibleKeys() throws Exception {
        String json = mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + READER)).andReturn().getResponse().getContentAsString();
        List<String> keys = JsonPath.read(json, "$.chunks[*].documentKey");
        return keys.stream().distinct().toList();
    }

    private List<String> visibleChunkIds() throws Exception {
        String json = mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + READER)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.chunks[*].chunkId");
    }

    private List<String> versions() {
        return jdbc.sql("select d.external_key || ':' || v.version_no from document_version v join document d on d.id = v.document_id").query(String.class).list();
    }

    private List<String> chunkVersions() {
        return jdbc.sql("""
                        select distinct d.external_key || ':' || v.version_no
                        from chunk c join document_version v on v.id = c.version_id join document d on d.id = v.document_id
                        """)
                .query(String.class)
                .list();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}

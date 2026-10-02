package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.EmbeddingClient;

import java.util.List;
import java.util.Map;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ExistenceLeakIT.FakeModels.class})
class ExistenceLeakIT {

    private static final String READER = DemoTokens.sign(
            Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query debug", "clearance", "internal", "department", "engineering"),
            "grounded-access-demo");

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

    @BeforeEach
    void corpus() throws Exception {
        jdbc.sql("truncate audit_event, query_execution, ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        ingest("northstar", "handbook", "\"classification\":\"internal\"", "handbook");
        ingest("northstar", "salary-bands", "\"classification\":\"confidential\"", "zebracrossing");
        ingest("northstar", "support-notes", "\"allowedDepartments\":[\"support\"]", "zebracrossing");
        ingest("northstar", "retired", "\"classification\":\"public\"", "zebracrossing");
        ingest("northstar", "removed", "\"classification\":\"public\"", "zebracrossing");
        ingest("external", "partner-doc", "\"classification\":\"public\"", "zebracrossing");
        String admin = DemoTokens.token("admin", "northstar", "admin");
        mvc.perform(patch("/api/v1/documents/retired").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/documents/removed").header("Authorization", "Bearer " + admin)).andExpect(status().isNoContent());
    }

    @Test
    void anAuthorizedDocumentCanBeRead() throws Exception {
        mvc.perform(get("/api/v1/documents/handbook").header("Authorization", "Bearer " + READER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("handbook"))
                .andExpect(jsonPath("$.title").value("handbook"))
                .andExpect(jsonPath("$.versionNo").value(1));
    }

    @Test
    void everyReasonADocumentCannotBeReadLooksExactlyLikeADocumentThatDoesNotExist() throws Exception {
        MockHttpServletResponse missing = read("no-such-document");

        assertThat(missing.getStatus()).isEqualTo(404);
        for (String key : List.of("salary-bands", "support-notes", "retired", "removed", "partner-doc", "Not_A_Valid_Key")) {
            MockHttpServletResponse hidden = read(key);
            assertThat(hidden.getStatus()).as(key).isEqualTo(404);
            assertThat(hidden.getContentAsString().replace(key, "KEY")).as(key).isEqualTo(missing.getContentAsString().replace("no-such-document", "KEY"));
            assertThat(hidden.getHeaderNames().stream().filter(name -> !name.equals("X-Trace-Id")).sorted().toList()).as(key)
                    .isEqualTo(missing.getHeaderNames().stream().filter(name -> !name.equals("X-Trace-Id")).sorted().toList());
        }
    }

    @Test
    void aSearchThatOnlyHiddenDocumentsCouldAnswerLooksLikeASearchNothingAnswers() throws Exception {
        String hidden = search("zebracrossing");
        String nothing = search("xylophonequartet");

        assertThat(hidden).isEqualTo(nothing).contains("\"results\":[]").doesNotContain("filtered", "total", "hidden");
    }

    @Test
    void readsAreAuditedWithoutSayingWhyADocumentWasNotVisible() throws Exception {
        read("handbook");
        read("salary-bands");
        read("no-such-document");
        read("Not_A_Valid_Key");

        List<String> events = jdbc.sql("select resource_id || ' ' || decision || ' ' || attributes::text from audit_event where action = 'document.read' "
                + "order by occurred_at").query(String.class).list();

        assertThat(events).containsExactly("handbook allow {}", "salary-bands deny {}", "no-such-document deny {}");
    }

    private MockHttpServletResponse read(String key) throws Exception {
        return mvc.perform(get("/api/v1/documents/" + key).header("Authorization", "Bearer " + READER)).andReturn().getResponse();
    }

    /**
     * A keyword search as an ordinary user, with the fields removed that differ between any two requests. The keyword channel is used because it
     * returns nothing when nothing matches; a vector search always returns the nearest visible chunks, whatever the query.
     */
    private String search(String query) throws Exception {
        String plain = DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query", "clearance", "internal", "department", "engineering"),
                "grounded-access-demo");
        String body = mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + plain).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"strategy\":\"sparse-only\",\"asOf\":\"2026-01-01T00:00:00Z\"}".formatted(query)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll("\"executionId\":\"[^\"]+\"", "").replaceAll("\"traceId\":\"[^\"]+\"", "");
    }

    private void ingest(String tenant, String key, String labels, String word) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\",\"content\":\"# Doc\\n\\nThe %s policy text.\\n\",%s}]}".formatted(key, key, word, labels);
        mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + DemoTokens.token("admin", tenant, "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        worker.drain();
    }
}

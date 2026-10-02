package io.groundedaccess.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuditIT.FakeModels.class})
class AuditIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String READER = DemoTokens.token("alice", "northstar", "query debug");

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final String SECRET_QUERY = "zebracrossing salary negotiation";

    private static final AtomicBoolean QUERY_EMBEDDING_DOWN = new AtomicBoolean();

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    if (inputType == InputType.QUERY && QUERY_EMBEDDING_DOWN.get()) {
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

    @BeforeEach
    void reset() {
        jdbc.sql("truncate audit_event, query_execution, ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        QUERY_EMBEDDING_DOWN.set(false);
    }

    @AfterEach
    void restoreAuditTable() {
        jdbc.sql("alter table if exists audit_event_broken rename to audit_event").update();
    }

    @Test
    void aSearchLeavesAnExecutionRecordAndAnAuditEventUnderTheCallersTraceId() throws Exception {
        ingest("hr-handbook", "Unmistakable Handbook Title", "# Handbook\\n\\nThe salary negotiation guide for zebracrossing teams.\\n");

        String body = search(READER, "hybrid-rrf").andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", TRACE))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String executionId = JsonPath.read(body, "$.executionId");

        Map<String, Object> execution = jdbc.sql("select * from query_execution").query().singleRow();
        assertThat(execution).containsEntry("id", java.util.UUID.fromString(executionId))
                .containsEntry("tenant_id", "northstar")
                .containsEntry("principal_id", "alice")
                .containsEntry("trace_id", TRACE)
                .containsEntry("policy_version", "abac/1")
                .containsEntry("embedding_model", "hashing@test")
                .containsEntry("status", "ok")
                .containsEntry("result_count", 1);
        Map<String, Object> event = jdbc.sql("select action, resource_id, decision, trace_id, attributes::text as attributes from audit_event where action = 'retrieval.search'")
                .query().singleRow();
        assertThat(event).containsEntry("resource_id", executionId).containsEntry("decision", "allow").containsEntry("trace_id", TRACE);
        assertThat(String.valueOf(event.get("attributes"))).contains("\"strategy\": \"hybrid-rrf\"", "\"documents\": [\"hr-handbook@v1\"]", "\"resultCount\": 1");
    }

    @Test
    void neitherTableStoresTheQueryTheChunkTextOrTheDocumentTitle() throws Exception {
        ingest("hr-handbook", "Unmistakable Handbook Title", "# Handbook\\n\\nThe salary negotiation guide for zebracrossing teams.\\n");
        search(READER, "hybrid-rrf").andExpect(jsonPath("$.results.length()").value(1));
        mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + READER)).andExpect(status().isOk());

        String stored = String.join("\n", jdbc.sql("select a::text from audit_event a union all select q::text from query_execution q").query(String.class).list());

        assertThat(stored).contains("retrieval.search", "retrieval.list_chunks", "ingestion.submit")
                .doesNotContainIgnoringCase("zebracrossing")
                .doesNotContainIgnoringCase("negotiation")
                .doesNotContainIgnoringCase("Unmistakable");
    }

    @Test
    void aSearchThatCannotBeAuditedReturnsNothing() throws Exception {
        ingest("hr-handbook", "Handbook", "# Handbook\\n\\nThe salary negotiation guide for zebracrossing teams.\\n");
        jdbc.sql("alter table audit_event rename to audit_event_broken").update();

        search(READER, "sparse-only").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.results").doesNotExist())
                .andExpect(header().string("X-Trace-Id", TRACE));
        mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + READER)).andExpect(status().isServiceUnavailable());

        assertThat(jdbc.sql("select count(*) from query_execution").query(Integer.class).single()).as("the execution record rolled back with its event").isZero();
    }

    @Test
    void anAdministrativeChangeThatCannotBeAuditedDoesNotHappen() throws Exception {
        ingest("hr-handbook", "Handbook", "# Handbook\\n\\nThe salary negotiation guide for zebracrossing teams.\\n");
        jdbc.sql("alter table audit_event rename to audit_event_broken").update();

        mvc.perform(patch("/api/v1/documents/hr-handbook").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}")).andExpect(status().isServiceUnavailable());
        mvc.perform(delete("/api/v1/documents/hr-handbook").header("Authorization", "Bearer " + ADMIN)).andExpect(status().isServiceUnavailable());
        submit("another", "Another", "# Another\\n\\nText.\\n").andExpect(status().isServiceUnavailable());

        assertThat(jdbc.sql("select status from document where external_key = 'hr-handbook'").query(String.class).single()).isEqualTo("active");
        assertThat(jdbc.sql("select count(*) from ingestion_job where document_count = 1 and status = 'queued'").query(Integer.class).single()).isZero();
    }

    @Test
    void administrativeChangesAreAuditedWithTheActingPrincipal() throws Exception {
        ingest("hr-handbook", "Handbook", "# Handbook\\n\\nText.\\n");
        mvc.perform(patch("/api/v1/documents/hr-handbook").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/documents/hr-handbook").header("Authorization", "Bearer " + ADMIN)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/documents/hr-handbook").header("Authorization", "Bearer " + ADMIN)).andExpect(status().isNotFound());

        List<String> events = jdbc.sql("select action || ' ' || principal_id || ' ' || resource_id || ' ' || coalesce(attributes ->> 'status', '-') "
                + "from audit_event where resource_type <> 'ingestion_job' order by occurred_at").query(String.class).list();

        assertThat(events).containsExactly("document.status_change admin hr-handbook disabled", "document.delete admin hr-handbook deleted");
        assertThat(jdbc.sql("select attributes ->> 'documents' from audit_event where action = 'ingestion.submit'").query(String.class).single())
                .isEqualTo("[\"hr-handbook\"]");
    }

    @Test
    void aDegradedSearchIsRecordedAsDegraded() throws Exception {
        ingest("hr-handbook", "Handbook", "# Handbook\\n\\nThe salary negotiation guide for zebracrossing teams.\\n");
        QUERY_EMBEDDING_DOWN.set(true);

        search(READER, "hybrid-rrf").andExpect(status().isOk());

        Map<String, Object> execution = jdbc.sql("select status, degraded_reasons::text as reasons, embedding_model from query_execution").query().singleRow();
        assertThat(execution).containsEntry("status", "degraded").containsEntry("reasons", "{dense_unavailable}").containsEntry("embedding_model", null);
    }

    @Test
    void anExecutionRecordIsReadableOnlyByThePrincipalThatMadeTheRequest() throws Exception {
        ingest("hr-handbook", "Handbook", "# Handbook\\n\\nThe salary negotiation guide for zebracrossing teams.\\n");
        String executionId = JsonPath.read(search(READER, "sparse-only").andReturn().getResponse().getContentAsString(), "$.executionId");

        mvc.perform(get("/api/v1/query-executions/" + executionId).header("Authorization", "Bearer " + READER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(jsonPath("$.planHash", matchesPattern("[0-9a-f]{16}")))
                .andExpect(jsonPath("$.policyVersion").value("abac/1"))
                .andExpect(jsonPath("$.status").value("ok"));
        for (String other : List.of(DemoTokens.token("bob", "northstar", "query"), DemoTokens.token("alice", "external", "query"))) {
            mvc.perform(get("/api/v1/query-executions/" + executionId).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        }
    }

    @Test
    void aRejectedRequestStillCarriesATraceId() throws Exception {
        mvc.perform(post("/api/v1/retrieval/search").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f]{32}")));
    }

    private ResultActions search(String token, String strategy) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + token)
                .header("traceparent", "00-" + TRACE + "-00f067aa0ba902b7-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"strategy\":\"%s\"}".formatted(SECRET_QUERY, strategy)));
    }

    private ResultActions submit(String key, String title, String content) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\",\"content\":\"%s\"}]}".formatted(key, title, content);
        return mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void ingest(String key, String title, String content) throws Exception {
        submit(key, title, content).andExpect(status().isAccepted());
        worker.drain();
    }
}

package io.groundedaccess.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.StubModelServer;
import io.groundedaccess.StubModelServer.Behaviour;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.ingestion.IngestionWorker;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One deadline bounds the model calls of a search (ADR-0010): a slow query embedding is given up on in time, and reranking that could not
 * finish before the deadline is not started.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.retrieval.deadline=1200ms",
        "ga.model-service.query-timeout=700ms", "ga.model-service.rerank-timeout=800ms", "ga.model-service.connect-timeout=300ms"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SearchDeadlineIT {

    private static final StubModelServer MODELS = new StubModelServer();

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String READER = DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query", "clearance", "internal"),
            "grounded-access-demo");

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("ga.model-service.base-url", MODELS::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        MODELS.stop();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionWorker worker;

    @BeforeEach
    void corpus() throws Exception {
        MODELS.reset();
        jdbc.sql("truncate query_execution, ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"documents":[{"key":"hr-volunteer-policy","title":"Volunteering","content":"Employees receive two paid volunteer days.",
                        "format":"text","classification":"internal"}]}""")).andExpect(status().isAccepted());
        worker.drain();
        MODELS.reset();
    }

    @Test
    void aSlowEmbeddingIsGivenUpOnAndRerankingThatCannotFinishInTimeIsNotStarted() throws Exception {
        MODELS.behave(StubModelServer.EMBED, Behaviour.HANG);
        long started = System.nanoTime();

        String body = mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"paid volunteer days\",\"strategy\":\"hybrid-rrf-rerank\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Duration took = Duration.ofNanos(System.nanoTime() - started);
        List<String> degraded = JsonPath.read(body, "$.degraded");
        assertThat(degraded).containsExactly("dense_unavailable", "rerank_unavailable");
        assertThat(took).as("the 1.2 s deadline plus database time").isLessThan(Duration.ofMillis(1500));
        assertThat(MODELS.calls(StubModelServer.RERANK)).as("700 ms of embedding left less than the 800 ms a rerank call needs").isZero();
    }

    @Test
    void withTimeLeftTheSearchIsCompleteAndTellsTheModelServiceHowLongItWaits() throws Exception {
        String body = mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"paid volunteer days\",\"strategy\":\"hybrid-rrf-rerank\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat((List<?>) JsonPath.read(body, "$.degraded")).isEmpty();
        assertThat(MODELS.lastDeadline(StubModelServer.EMBED)).isBetween(1L, 700L);
        assertThat(MODELS.lastDeadline(StubModelServer.RERANK)).isEqualTo(800L);
    }
}

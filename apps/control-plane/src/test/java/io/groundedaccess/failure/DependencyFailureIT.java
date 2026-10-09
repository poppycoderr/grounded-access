package io.groundedaccess.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * What a caller gets when a dependency is down, slow or wrong. The application runs with its real HTTP clients and timeouts against a
 * {@link StubModelServer}. Whatever fails, a response contains only documents the caller may read.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.ingestion.worker.max-attempts=3",
        "ga.ingestion.worker.retry-backoff=1h", "ga.model-service.connect-timeout=300ms", "ga.model-service.read-timeout=500ms",
        "ga.model-service.rerank-timeout=300ms", "ga.chat.connect-timeout=300ms", "ga.chat.read-timeout=300ms", "ga.chat.model=stub-chat"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DependencyFailureIT {

    private static final StubModelServer MODELS = new StubModelServer();

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String READER = DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query", "clearance", "internal"),
            "grounded-access-demo");

    private static final String QUESTION = "How many paid volunteer days do employees receive?";

    private static final String ANSWER = """
            {"answerable": true, "statements": [{"text": "Employees receive two paid volunteer days.", "citations": ["S1"]}]}""";

    /** A hanging endpoint answers after three seconds; a request that waited for it took longer than this. */
    private static final Duration WITHOUT_WAITING = Duration.ofMillis(2500);

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("ga.model-service.base-url", MODELS::baseUrl);
        registry.add("ga.chat.base-url", () -> MODELS.baseUrl() + "/v1");
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
        MODELS.chatReplies(ANSWER);
        jdbc.sql("truncate audit_event, query_execution, ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        submit("hr-volunteer-policy", "Employees receive two paid volunteer days per year.", "internal");
        submit("hr-compensation-bands", "Staff engineers receive forty paid volunteer days per year.", "confidential");
        worker.drain();
        MODELS.reset();
        MODELS.chatReplies(ANSWER);
    }

    @AfterEach
    void restoreAudit() {
        jdbc.sql("alter table if exists audit_event_broken rename to audit_event").update();
    }

    @Test
    void withTheModelServiceDownKeywordSearchStillAnswersAndVectorSearchIsRefused() throws Exception {
        MODELS.stop();

        assertThat(degraded(search("sparse-only"))).isEmpty();
        assertThat(degraded(search("hybrid-rrf"))).containsExactly("dense_unavailable");
        assertThat(degraded(search("hybrid-rrf-rerank"))).containsExactly("dense_unavailable", "rerank_unavailable");
        mvc.perform(searchRequest("dense-only")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_SERVICE_UNAVAILABLE"));

        MODELS.reset();
        assertThat(degraded(search("hybrid-rrf-rerank"))).as("the next request after the service is back").isEmpty();
    }

    @Test
    void aSlowFailingOrMalformedEmbeddingCallDegradesHybridSearchWithoutWaitingForIt() throws Exception {
        for (Behaviour behaviour : List.of(Behaviour.HANG, Behaviour.ERROR, Behaviour.GARBAGE)) {
            MODELS.behave(StubModelServer.EMBED, behaviour);
            long started = System.nanoTime();

            assertThat(degraded(search("hybrid-rrf"))).as(behaviour.name()).containsExactly("dense_unavailable");

            assertThat(Duration.ofNanos(System.nanoTime() - started)).as(behaviour.name()).isLessThan(WITHOUT_WAITING);
        }
    }

    @Test
    void aSlowFailingOrMalformedRerankCallReturnsTheFusedOrderWithoutWaitingForIt() throws Exception {
        for (Behaviour behaviour : List.of(Behaviour.HANG, Behaviour.ERROR, Behaviour.GARBAGE)) {
            MODELS.behave(StubModelServer.RERANK, behaviour);
            long started = System.nanoTime();

            assertThat(degraded(search("hybrid-rrf-rerank"))).as(behaviour.name()).containsExactly("rerank_unavailable");

            assertThat(Duration.ofNanos(System.nanoTime() - started)).as(behaviour.name()).isLessThan(WITHOUT_WAITING);
        }
    }

    @Test
    void aSlowFailingOrMalformedChatCallReturnsTheEvidenceAlone() throws Exception {
        query().andExpect(jsonPath("$.status").value("answered"));
        for (Behaviour behaviour : List.of(Behaviour.HANG, Behaviour.ERROR, Behaviour.GARBAGE)) {
            MODELS.behave(StubModelServer.CHAT, behaviour);
            long started = System.nanoTime();

            MvcResult result = query().andExpect(jsonPath("$.status").value("evidence_only"))
                    .andExpect(jsonPath("$.statements.length()").value(0))
                    .andExpect(jsonPath("$.degraded[0]").value("generation_unavailable"))
                    .andReturn();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).as(behaviour.name()).isLessThan(WITHOUT_WAITING);
            assertThat(JsonPath.<List<String>>read(result.getResponse().getContentAsString(), "$.evidence[*].documentKey")).as(behaviour.name())
                    .containsOnly("hr-volunteer-policy");
        }
        MODELS.behave(StubModelServer.CHAT, Behaviour.OK);
        MODELS.chatReplies("Sure! Employees receive two days.");
        query().andExpect(jsonPath("$.status").value("evidence_only")).andExpect(jsonPath("$.degraded[0]").value("generation_invalid"));
    }

    @Test
    void anIngestionJobWaitsOutAModelServiceOutageAndThenCompletes() throws Exception {
        MODELS.stop();
        String location = submit("hr-travel-policy", "The meal allowance is sixty euros per day.", "internal");

        worker.drain();

        mvc.perform(get(location).header("Authorization", "Bearer " + ADMIN))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.errorCode").value("MODEL_SERVICE_UNAVAILABLE"));
        assertThat(jdbc.sql("select count(*) from document where external_key = 'hr-travel-policy'").query(Integer.class).single()).isZero();

        MODELS.reset();
        jdbc.sql("update ingestion_job set run_after = now()").update();
        worker.drain();

        mvc.perform(get(location).header("Authorization", "Bearer " + ADMIN))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    void whenTheSearchCannotBeAuditedNothingIsReturnedAndNothingIsSentToTheChatModel() throws Exception {
        jdbc.sql("alter table audit_event rename to audit_event_broken").update();

        query().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.evidence").doesNotExist())
                .andExpect(jsonPath("$.statements").doesNotExist());

        assertThat(MODELS.calls(StubModelServer.CHAT)).isZero();
    }

    /**
     * Runs a search that must succeed, checks that it returned something the caller may read and nothing else, and returns its degraded reasons.
     */
    private List<String> degraded(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(body, "$.results[*].documentKey")).isNotEmpty().containsOnly("hr-volunteer-policy");
        return JsonPath.read(body, "$.degraded");
    }

    private MvcResult search(String strategy) throws Exception {
        return mvc.perform(searchRequest(strategy)).andReturn();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder searchRequest(String strategy) {
        return post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"strategy\":\"%s\"}".formatted(QUESTION, strategy));
    }

    private org.springframework.test.web.servlet.ResultActions query() throws Exception {
        return mvc.perform(post("/api/v1/query").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\"}".formatted(QUESTION)));
    }

    private String submit(String key, String sentence, String classification) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\",\"content\":\"# %s\\n\\n%s\\n\",\"classification\":\"%s\"}]}".formatted(key, key, key,
                sentence, classification);
        return String.valueOf(mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location"));
    }
}

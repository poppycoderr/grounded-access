package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
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
import io.groundedaccess.modelclient.RerankClient;
import io.groundedaccess.modelclient.RerankScores;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

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

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.retrieval.rerank-candidates=4"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RerankIT.FakeModels.class})
class RerankIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String DEBUG = DemoTokens.sign(Map.of("sub", "eval", "tenant_id", "northstar", "scope", "query debug", "clearance", "internal"),
            "grounded-access-demo");

    private static final AtomicBoolean RERANKER_DOWN = new AtomicBoolean();

    private static final List<List<String>> SEEN = Collections.synchronizedList(new ArrayList<>());

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }

        /** Scores a passage by its position, so a working rerank stage returns its candidates in exactly the reverse of the fused order. */
        @Bean
        @Primary
        RerankClient reversingRerankClient() {
            return new RerankClient() {

                @Override
                public String modelName() {
                    return "reversing";
                }

                @Override
                public RerankScores score(String query, List<String> passages) {
                    if (RERANKER_DOWN.get()) {
                        throw new ResourceAccessException("model service unavailable");
                    }
                    SEEN.add(passages);
                    return new RerankScores("reversing", "r9", IntStream.range(0, passages.size()).mapToObj(i -> (double) i).toList());
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
    void corpus() throws Exception {
        jdbc.sql("truncate audit_event, query_execution, ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        RERANKER_DOWN.set(false);
        SEEN.clear();
        for (int i = 1; i <= 6; i++) {
            ingest("northstar", "policy-" + i, "The volunteer policy number %d gives %s.".formatted(i, "volunteer ".repeat(7 - i).strip() + " days"), "internal");
        }
        ingest("northstar", "secret-policy", "The volunteer policy for executives gives volunteer volunteer volunteer days.", "restricted");
        ingest("external", "other-policy", "The volunteer policy of another company gives volunteer days.", "public");
    }

    @Test
    void rerankingReordersTheTopFusedCandidatesAndLeavesTheRestBehindThem() throws Exception {
        List<String> fused = keys(search(DEBUG, "hybrid-rrf", 6));

        String body = search(DEBUG, "hybrid-rrf-rerank", 6).andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded", empty()))
                .andExpect(jsonPath("$.plan.rerankCandidates").value(6))
                .andExpect(jsonPath("$.plan.reranker").value("reversing"))
                .andReturn().getResponse().getContentAsString();

        assertThat(fused).hasSize(6);
        assertThat(JsonPath.<List<String>>read(body, "$.results[*].documentKey")).containsExactlyElementsOf(fused.reversed());
        assertThat(JsonPath.<List<Integer>>read(body, "$.results[*].fusedRank")).containsExactly(6, 5, 4, 3, 2, 1);
        assertThat(JsonPath.<List<Integer>>read(body, "$.results[*].rank")).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(JsonPath.<List<Double>>read(body, "$.results[*].rerankScore")).containsExactly(5.0, 4.0, 3.0, 2.0, 1.0, 0.0);
    }

    @Test
    void onlyTheConfiguredNumberOfCandidatesIsSentToTheCrossEncoder() throws Exception {
        List<String> fused = keys(search(DEBUG, "hybrid-rrf", 2));

        List<String> reranked = keys(search(DEBUG, "hybrid-rrf-rerank", 2));

        assertThat(SEEN).hasSize(1);
        assertThat(SEEN.getFirst()).as("rerank-candidates is 4").hasSize(4);
        assertThat(reranked).hasSize(2).doesNotContainAnyElementsOf(fused);
    }

    @Test
    void theCrossEncoderOnlyEverSeesAuthorizedPassages() throws Exception {
        search(DEBUG, "hybrid-rrf-rerank", 6).andExpect(status().isOk());

        assertThat(SEEN).isNotEmpty();
        assertThat(SEEN).allSatisfy(passages -> assertThat(passages).noneMatch(text -> text.contains("executives") || text.contains("another company")));
    }

    @Test
    void whenRerankingFailsTheFusedOrderIsReturnedAndTheResponseSaysSo() throws Exception {
        List<String> fused = keys(search(DEBUG, "hybrid-rrf", 6));
        RERANKER_DOWN.set(true);

        ResultActions degraded = search(DEBUG, "hybrid-rrf-rerank", 6).andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded", contains("rerank_unavailable")))
                .andExpect(jsonPath("$.results[0].rerankScore", nullValue()));

        assertThat(keys(degraded)).containsExactlyElementsOf(fused);
        Map<String, Object> execution = jdbc.sql("select status, reranker_model, degraded_reasons::text as reasons from query_execution order by created_at desc limit 1")
                .query().singleRow();
        assertThat(execution).containsEntry("status", "degraded").containsEntry("reranker_model", null).containsEntry("reasons", "{rerank_unavailable}");
    }

    @Test
    void theExecutionRecordNamesTheRerankerAndOrdinaryUsersSeeNoRerankDetails() throws Exception {
        String executionId = JsonPath.read(search(DEBUG, "hybrid-rrf-rerank", 3).andReturn().getResponse().getContentAsString(), "$.executionId");

        mvc.perform(get("/api/v1/query-executions/" + executionId).header("Authorization", "Bearer " + DEBUG))
                .andExpect(jsonPath("$.rerankerModel").value("reversing@r9"))
                .andExpect(jsonPath("$.status").value("ok"));
        search(DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query", "clearance", "internal"), "grounded-access-demo"),
                "hybrid-rrf-rerank", 3)
                .andExpect(jsonPath("$.plan", nullValue()))
                .andExpect(jsonPath("$.results[0].rerankScore", nullValue()))
                .andExpect(jsonPath("$.results[0].fusedRank", nullValue()));
    }

    private ResultActions search(String token, String strategy, int k) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"volunteer policy days\",\"strategy\":\"%s\",\"k\":%d}".formatted(strategy, k)));
    }

    private static List<String> keys(ResultActions response) throws Exception {
        return JsonPath.read(response.andReturn().getResponse().getContentAsString(), "$.results[*].documentKey");
    }

    private void ingest(String tenant, String key, String sentence, String classification) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\",\"content\":\"# Policy\\n\\n%s\\n\",\"classification\":\"%s\"}]}"
                .formatted(key, key, sentence, classification);
        mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + DemoTokens.token("admin", tenant, "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        worker.drain();
    }
}

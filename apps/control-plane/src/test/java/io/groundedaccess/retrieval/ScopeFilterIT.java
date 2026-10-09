package io.groundedaccess.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.WordOverlapRerankClient;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.RerankClient;

import java.util.HashMap;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScopeFilterIT.FakeModels.class})
class ScopeFilterIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        RerankClient wordOverlapRerankClient() {
            return new WordOverlapRerankClient();
        }

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
    void reset() {
        jdbc.sql("truncate ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
    }

    @Test
    void theRegionDefaultsToThePrincipalsAndCanBeChangedByTheRequest() throws Exception {
        ingest("holidays-eu", "\"appliesToRegions\":[\"EU\"]");
        ingest("holidays-us", "\"appliesToRegions\":[\"US\"]");
        ingest("holidays-global", "\"classification\":\"public\"");

        assertThat(found(principal("region", "EU"), "")).containsExactly("holidays-eu", "holidays-global");
        assertThat(found(principal("region", "US"), "")).containsExactly("holidays-global", "holidays-us");
        assertThat(found(principal("region", "EU"), ",\"region\":\"US\"")).containsExactly("holidays-global", "holidays-us");
        assertThat(found(principal(), "")).as("no region known, so no region filter").containsExactly("holidays-eu", "holidays-global", "holidays-us");
    }

    @Test
    void aDocumentIsInScopeOnlyInsideItsValidityWindow() throws Exception {
        ingest("travel-2025", "\"validFrom\":\"2025-01-01T00:00:00Z\",\"validTo\":\"2026-01-01T00:00:00Z\"");
        ingest("travel-2026", "\"validFrom\":\"2026-01-01T00:00:00Z\"");
        ingest("travel-2099", "\"validFrom\":\"2099-01-01T00:00:00Z\"");

        assertThat(found(principal(), "")).containsExactly("travel-2026");
        assertThat(found(principal(), ",\"asOf\":\"2025-06-01T00:00:00Z\"")).containsExactly("travel-2025");
        assertThat(found(principal(), ",\"asOf\":\"2025-12-31T23:59:59Z\"")).containsExactly("travel-2025");
        assertThat(found(principal(), ",\"asOf\":\"2026-01-01T00:00:00Z\"")).as("the end of a window is exclusive").containsExactly("travel-2026");
        assertThat(found(principal(), ",\"asOf\":\"2024-06-01T00:00:00Z\"")).isEmpty();
    }

    @Test
    void asOfNeverBringsBackAReplacedVersion() throws Exception {
        ingest("handbook", "\"classification\":\"public\"");
        ingestContent("handbook", "# Handbook\\n\\nThe company handbook policy, second edition.\\n", "\"classification\":\"public\"");

        search(principal(), "sparse-only", ",\"asOf\":\"2020-01-01T00:00:00Z\"").andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].versionNo").value(2))
                .andExpect(jsonPath("$.scope.asOf").value("2020-01-01T00:00:00Z"));
    }

    @Test
    void scopeNarrowsWhatIsRelevantButNeverWidensWhatIsAuthorized() throws Exception {
        ingest("secret-eu", "\"classification\":\"confidential\",\"appliesToRegions\":[\"EU\"],\"validTo\":\"2026-01-01T00:00:00Z\"");
        Map<String, Object> reader = principal("clearance", "internal", "region", "EU");

        assertThat(found(reader, ",\"region\":\"EU\",\"asOf\":\"2025-06-01T00:00:00Z\"")).isEmpty();
        assertThat(listed(reader, "?includeOutOfScope=true")).as("dropping the scope keeps the authorization predicate").isEmpty();
        assertThat(listed(principal("clearance", "confidential"), "")).as("expired, so out of scope today").isEmpty();
        assertThat(listed(principal("clearance", "confidential"), "?includeOutOfScope=true")).containsExactly("secret-eu");
        assertThat(listed(principal("clearance", "confidential"), "?asOf=2025-06-01T00:00:00Z&region=EU")).containsExactly("secret-eu");
    }

    @Test
    void aChangeOfScopeIsANewVersionThatKeepsTheChunks() throws Exception {
        ingest("handbook", "\"classification\":\"public\"");

        ingest("handbook", "\"classification\":\"public\",\"validTo\":\"2020-01-01T00:00:00Z\"").andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.chunks").value(0));

        assertThat(found(principal(), "")).isEmpty();
        ingest("handbook", "\"classification\":\"public\",\"validTo\":\"2020-01-01T00:00:00Z\"").andExpect(jsonPath("$.unchanged").value(1));
    }

    @Test
    void rejectsAValidityWindowThatEndsBeforeItStarts() throws Exception {
        submit("doc", "# Doc\\n\\nText.\\n", "\"validFrom\":\"2026-01-01T00:00:00Z\",\"validTo\":\"2025-01-01T00:00:00Z\"").andExpect(status().isBadRequest());
    }

    /**
     * The document keys every strategy returns for the scope given in the request, which must be the same for all three.
     */
    private List<String> found(Map<String, Object> claims, String scope) throws Exception {
        List<String> expected = null;
        for (String strategy : List.of("sparse-only", "dense-only", "hybrid-rrf", "hybrid-rrf-rerank")) {
            String body = search(claims, strategy, scope).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> keys = JsonPath.<List<String>>read(body, "$.results[*].documentKey").stream().sorted().toList();
            assertThat(expected == null || expected.equals(keys)).as(strategy).isTrue();
            expected = keys;
        }
        return expected;
    }

    private List<String> listed(Map<String, Object> claims, String query) throws Exception {
        String body = mvc.perform(get("/api/v1/retrieval/chunks" + query).header("Authorization", "Bearer " + DemoTokens.sign(claims, "grounded-access-demo")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.<List<String>>read(body, "$.chunks[*].documentKey").stream().sorted().distinct().toList();
    }

    private ResultActions search(Map<String, Object> claims, String strategy, String scope) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + DemoTokens.sign(claims, "grounded-access-demo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"company handbook policy\",\"strategy\":\"%s\",\"k\":50%s}".formatted(strategy, scope)));
    }

    private static Map<String, Object> principal(Object... claims) {
        Map<String, Object> all = new HashMap<>(Map.of("sub", "reader", "tenant_id", "northstar", "scope", "query debug"));
        for (int i = 0; i < claims.length; i += 2) {
            all.put((String) claims[i], claims[i + 1]);
        }
        return all;
    }

    private ResultActions submit(String key, String content, String metadata) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\",\"content\":\"%s\",%s}]}".formatted(key, key, content, metadata);
        return mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ingest(String key, String metadata) throws Exception {
        return ingestContent(key, "# Handbook\\n\\nThe company handbook policy.\\n", metadata);
    }

    private ResultActions ingestContent(String key, String content, String metadata) throws Exception {
        String location = submit(key, content, metadata).andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
        worker.drain();
        return mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$.status").value("succeeded"));
    }
}

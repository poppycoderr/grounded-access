package io.groundedaccess.retrieval;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RetrievalIT.FakeModels.class})
class RetrievalIT {

    private static final String VOLUNTEER_POLICY = """
            # Volunteer Policy

            ## European Union

            EU employees receive two paid volunteer days per calendar year.
            """;

    /** While true, embedding a query fails as if the model service were down; ingestion keeps working. */
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
    void resetCorpus() {
        jdbc.sql("truncate chunk, document_version, document, tenant cascade").update();
        QUERY_EMBEDDING_DOWN.set(false);
    }

    @Test
    void neverReturnsAnotherTenantsChunksFromEitherChannel() throws Exception {
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY).andExpect(jsonPath("$.status").value("succeeded"));
        ingest("external", "public-faq", "# FAQ\n\nVolunteer days are described in each company's handbook.\n").andExpect(jsonPath("$.status").value("succeeded"));

        for (String strategy : new String[] {"sparse-only", "dense-only", "hybrid-rrf"}) {
            search("mallory", "external", "query", "How many paid volunteer days do EU employees receive?", strategy)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results", hasSize(1)))
                    .andExpect(jsonPath("$.results[*].documentKey", everyItem(is("public-faq"))));
        }
    }

    @Test
    void sparseSearchMatchesQuestionsThatShareOnlySomeTerms() throws Exception {
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY);

        search("alice", "northstar", "query", "How many paid volunteer days do EU employees receive?", "sparse-only")
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].sectionPath").value("Volunteer Policy > European Union"))
                .andExpect(jsonPath("$.strategy").value("sparse-only"))
                .andExpect(jsonPath("$.policyVersion").value("abac/1"));
    }

    @Test
    void reingestingUnchangedContentIsIdempotentAndNewVersionsReplaceOldChunks() throws Exception {
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY).andExpect(jsonPath("$.created").value(1));
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY).andExpect(jsonPath("$.unchanged").value(1));
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY.replace("two", "three")).andExpect(jsonPath("$.updated").value(1));

        search("alice", "northstar", "query", "paid volunteer days", "sparse-only")
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].versionNo").value(2))
                .andExpect(jsonPath("$.results[0].text", startsWith("EU employees receive three")));
    }

    @Test
    void plainTextIsChunkedWithoutHeadingsAndResultsNameTheChunkerThatProducedThem() throws Exception {
        String faq = "# Volunteer FAQ\n\nEU employees receive two paid volunteer days per calendar year.\n";
        ingest("northstar", "volunteer-faq", faq, "text").andExpect(jsonPath("$.created").value(1));

        search("alice", "northstar", "query", "paid volunteer days", "sparse-only")
                .andExpect(jsonPath("$.results[0].sectionPath").value(""))
                .andExpect(jsonPath("$.results[0].chunkerVersion").value("text/2"));

        // The same text in another format is chunked differently, so it is a new version rather than unchanged.
        ingest("northstar", "volunteer-faq", faq, "markdown").andExpect(jsonPath("$.updated").value(1));
        ingest("northstar", "volunteer-faq", faq, "markdown").andExpect(jsonPath("$.unchanged").value(1));
        mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + DemoTokens.token("eval", "northstar", "query debug")))
                .andExpect(jsonPath("$.chunks[0].sectionPath").value("Volunteer FAQ"))
                .andExpect(jsonPath("$.chunks[0].chunkerVersion").value("markdown/2"));
    }

    @Test
    void rejectsAnUnknownDocumentFormat() throws Exception {
        submit(DemoTokens.token("admin", "northstar", "admin"), "doc", "text", "html").andExpect(status().isBadRequest());
    }

    @Test
    void hybridFusesBothChannelsAndExplainsTheRankingOnlyToDebugTokens() throws Exception {
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY);
        ingest("northstar", "hr-travel-policy", "# Travel\n\n## Meals\n\nThe meal allowance is 60 EUR.\n");

        search("eval", "northstar", "query debug", "paid volunteer days", "hybrid-rrf")
                .andExpect(jsonPath("$.strategy").value("hybrid-rrf"))
                .andExpect(jsonPath("$.degraded", empty()))
                .andExpect(jsonPath("$.planHash").value("bd71e8ef3c45267b"))
                .andExpect(jsonPath("$.plan.rrfK").value(60))
                .andExpect(jsonPath("$.plan.candidates").value(50))
                .andExpect(jsonPath("$.results[0].documentKey").value("hr-volunteer-policy"))
                .andExpect(jsonPath("$.results[0].rank").value(1))
                .andExpect(jsonPath("$.results[0].sparseRank").value(1))
                .andExpect(jsonPath("$.results[0].denseRank").value(1))
                .andExpect(jsonPath("$.results[0].score", closeTo(2.0 / 61, 1e-9), Double.class))
                .andExpect(jsonPath("$.results[1].documentKey").value("hr-travel-policy"))
                .andExpect(jsonPath("$.results[1].sparseRank", nullValue()))
                .andExpect(jsonPath("$.results[1].denseRank").value(2));

        search("alice", "northstar", "query", "paid volunteer days", "hybrid-rrf")
                .andExpect(jsonPath("$.planHash").value("bd71e8ef3c45267b"))
                .andExpect(jsonPath("$.plan", nullValue()))
                .andExpect(jsonPath("$.results[0].score", nullValue()))
                .andExpect(jsonPath("$.results[0].sparseRank", nullValue()))
                .andExpect(jsonPath("$.results[0].denseRank", nullValue()));
    }

    @Test
    void hybridFallsBackToTheSparseChannelAndSaysSoWhenTheQueryCannotBeEmbedded() throws Exception {
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY);
        QUERY_EMBEDDING_DOWN.set(true);

        search("eval", "northstar", "query debug", "paid volunteer days", "hybrid-rrf")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded", contains("dense_unavailable")))
                .andExpect(jsonPath("$.results[0].documentKey").value("hr-volunteer-policy"))
                .andExpect(jsonPath("$.results[0].denseRank", nullValue()));
        search("eval", "northstar", "query debug", "paid volunteer days", "dense-only")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_SERVICE_UNAVAILABLE"));
    }

    @Test
    void returnsRawScoresOnlyToDebugTokens() throws Exception {
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY);

        search("alice", "northstar", "query", "volunteer days", "dense-only").andExpect(jsonPath("$.results[0].score", nullValue()));
        search("eval", "northstar", "query debug", "volunteer days", "dense-only").andExpect(jsonPath("$.results[0].score", not(nullValue())));
    }

    @Test
    void rejectsMissingWrongOrUnderScopedTokens() throws Exception {
        String body = "{\"query\":\"volunteer\",\"strategy\":\"sparse-only\"}";
        String noTenant = DemoTokens.sign(Map.of("sub", "alice", "scope", "query"), "grounded-access-demo");
        String wrongIssuer = DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query"), "someone-else");

        mvc.perform(post("/api/v1/retrieval/search").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(searchWith(noTenant, body)).andExpect(status().isUnauthorized());
        mvc.perform(searchWith(wrongIssuer, body)).andExpect(status().isUnauthorized());
        mvc.perform(searchWith(DemoTokens.token("admin", "northstar", "admin"), body)).andExpect(status().isForbidden());
        submit(DemoTokens.token("alice", "northstar", "query"), "doc", "text").andExpect(status().isForbidden());
    }

    @Test
    void listsOnlyTheCallersChunksPageByPageInAStableOrder() throws Exception {
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY);
        ingest("northstar", "hr-travel-policy", "# Travel\n\n## Meals\n\nThe meal allowance is 60 EUR per day.\n\n## Receipts\n\nSubmit receipts within 30 days.\n");
        ingest("external", "public-faq", "# FAQ\n\nVolunteer days are described in each company's handbook.\n");
        String token = DemoTokens.token("eval", "northstar", "query debug");

        mvc.perform(get("/api/v1/retrieval/chunks").param("limit", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks[*].documentKey", contains("hr-travel-policy", "hr-travel-policy")))
                .andExpect(jsonPath("$.next").value("hr-travel-policy:1:1"));
        mvc.perform(get("/api/v1/retrieval/chunks").param("limit", "2").param("after", "hr-travel-policy:1:1").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.chunks[*].documentKey", contains("hr-volunteer-policy")))
                .andExpect(jsonPath("$.next").doesNotExist());
    }

    @Test
    void chunkListingNeedsTheDebugScopeAndAValidCursor() throws Exception {
        String query = DemoTokens.token("alice", "northstar", "query");
        String debug = DemoTokens.token("eval", "northstar", "query debug");

        mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + query)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/retrieval/chunks").param("after", "../etc").header("Authorization", "Bearer " + debug))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get("/api/v1/retrieval/chunks").param("limit", "5000").header("Authorization", "Bearer " + debug)).andExpect(status().isBadRequest());
    }

    /**
     * Submits a one-document job, runs the worker, and returns the finished job.
     */
    private ResultActions ingest(String tenant, String key, String content) throws Exception {
        return ingest(tenant, key, content, "markdown");
    }

    private ResultActions ingest(String tenant, String key, String content, String format) throws Exception {
        String token = DemoTokens.token("admin", tenant, "admin");
        String location = submit(token, key, content, format).andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
        worker.drain();
        return mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private ResultActions submit(String token, String key, String content) throws Exception {
        return submit(token, key, content, "markdown");
    }

    private ResultActions submit(String token, String key, String content, String format) throws Exception {
        String body = """
                {"documents":[{"key":"%s","title":"%s","content":%s,"format":"%s"}]}
                """.formatted(key, key, quote(content), format);
        return mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions search(String subject, String tenant, String scope, String query, String strategy) throws Exception {
        String body = "{\"query\":%s,\"strategy\":\"%s\"}".formatted(quote(query), strategy);
        return mvc.perform(searchWith(DemoTokens.token(subject, tenant, scope), body));
    }

    private static RequestBuilder searchWith(String token, String body) {
        return post("/api/v1/retrieval/search").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}

package io.groundedaccess.retrieval;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.modelclient.EmbeddingClient;

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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RetrievalIT.FakeModels.class})
class RetrievalIT {

    private static final String VOLUNTEER_POLICY = """
            # Volunteer Policy

            ## European Union

            EU employees receive two paid volunteer days per calendar year.
            """;

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

    @BeforeEach
    void resetCorpus() {
        jdbc.sql("truncate chunk, document_version, document, tenant cascade").update();
    }

    @Test
    void neverReturnsAnotherTenantsChunksFromEitherChannel() throws Exception {
        ingest("northstar", "hr-volunteer-policy", VOLUNTEER_POLICY).andExpect(status().isOk());
        ingest("external", "public-faq", "# FAQ\n\nVolunteer days are described in each company's handbook.\n").andExpect(status().isOk());

        for (String strategy : new String[] {"sparse-only", "dense-only"}) {
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
                .andExpect(jsonPath("$.results[0].channel").value("SPARSE"))
                .andExpect(jsonPath("$.policyVersion").value("tenant-only/1"));
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
        ingestWith(DemoTokens.token("alice", "northstar", "query"), "doc", "text").andExpect(status().isForbidden());
    }

    private ResultActions ingest(String tenant, String key, String content) throws Exception {
        return ingestWith(DemoTokens.token("admin", tenant, "admin"), key, content);
    }

    private ResultActions ingestWith(String token, String key, String content) throws Exception {
        String body = """
                {"documents":[{"key":"%s","title":"%s","content":%s}]}
                """.formatted(key, key, quote(content));
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

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
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.util.HashMap;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.ingestion.worker.max-attempts=1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LabelAuthorizationIT.FakeModels.class})
class LabelAuthorizationIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    /** While true, embedding passages fails as if the model service were down; queries still embed. */
    private static final AtomicBoolean PASSAGE_EMBEDDING_DOWN = new AtomicBoolean();

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    if (inputType == InputType.PASSAGE && PASSAGE_EMBEDDING_DOWN.get()) {
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
        jdbc.sql("truncate ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        PASSAGE_EMBEDDING_DOWN.set(false);
    }

    @Test
    void clearanceAdmitsDocumentsUpToThePrincipalsLevelOnEveryQueryPath() throws Exception {
        for (String level : List.of("public", "internal", "confidential", "restricted")) {
            ingest(level + "-doc", "\"classification\":\"%s\"".formatted(level)).andExpect(jsonPath("$.created").value(1));
        }

        assertThat(visibleTo(principal("clearance", "internal"))).containsExactly("internal-doc", "public-doc");
        assertThat(visibleTo(principal("clearance", "restricted"))).containsExactly("confidential-doc", "internal-doc", "public-doc", "restricted-doc");
        assertThat(visibleTo(principal())).containsExactly("public-doc");
        assertThat(visibleTo(principal("clearance", "top-secret"))).containsExactly("public-doc");
    }

    @Test
    void aDepartmentLabelAdmitsOnlyThatDepartmentAndAProjectLabelAnyOfItsProjects() throws Exception {
        ingest("open-doc", "\"classification\":\"public\"");
        ingest("eng-doc", "\"allowedDepartments\":[\"engineering\",\"security\"]");
        ingest("atlas-doc", "\"requiredProjects\":[\"atlas\",\"borealis\"]");

        assertThat(visibleTo(principal("department", "engineering"))).containsExactly("eng-doc", "open-doc");
        assertThat(visibleTo(principal("department", "support"))).containsExactly("open-doc");
        assertThat(visibleTo(principal("projects", List.of("borealis", "cygnus")))).containsExactly("atlas-doc", "open-doc");
        assertThat(visibleTo(principal("projects", List.of("cygnus")))).containsExactly("open-doc");
        assertThat(visibleTo(principal())).containsExactly("open-doc");
    }

    @Test
    void everyRuleMustHoldAtOnce() throws Exception {
        ingest("guarded-doc", "\"classification\":\"confidential\",\"allowedDepartments\":[\"engineering\"],\"requiredProjects\":[\"atlas\"]");

        assertThat(visibleTo(principal("clearance", "confidential", "department", "engineering", "projects", List.of("atlas")))).containsExactly("guarded-doc");
        assertThat(visibleTo(principal("clearance", "internal", "department", "engineering", "projects", List.of("atlas")))).isEmpty();
        assertThat(visibleTo(principal("clearance", "confidential", "department", "support", "projects", List.of("atlas")))).isEmpty();
        assertThat(visibleTo(principal("clearance", "confidential", "department", "engineering"))).isEmpty();
        assertThat(visibleTo(Map.of("sub", "outsider", "tenant_id", "external", "scope", "query debug", "clearance", "restricted", "department", "engineering",
                "projects", List.of("atlas")))).isEmpty();
    }

    @Test
    void hostileClaimValuesAreComparedAsDataAndMatchNothing() throws Exception {
        ingest("eng-doc", "\"classification\":\"internal\",\"allowedDepartments\":[\"engineering\"],\"requiredProjects\":[\"atlas\"]");

        assertThat(visibleTo(principal("clearance", "restricted' or '1'='1", "department", "x') or true --", "projects", List.of("atlas\"} or {\"")))).isEmpty();
    }

    @Test
    void aLabelChangeAppliesToTheNextQueryWithoutTheModelService() throws Exception {
        ingest("handbook", "\"classification\":\"public\"");
        Map<String, Object> reader = principal();
        List<String> chunkIds = chunkIds(reader);
        assertThat(visibleTo(reader)).containsExactly("handbook");

        PASSAGE_EMBEDDING_DOWN.set(true);
        ingest("handbook", "\"classification\":\"confidential\"").andExpect(jsonPath("$.updated").value(1)).andExpect(jsonPath("$.chunks").value(0));

        assertThat(visibleTo(reader)).isEmpty();
        Map<String, Object> cleared = principal("clearance", "confidential");
        assertThat(visibleTo(cleared)).containsExactly("handbook");
        assertThat(chunkIds(cleared)).as("the chunks moved to the new version instead of being embedded again").isEqualTo(chunkIds);
        search(cleared, "sparse-only").andExpect(jsonPath("$.results[0].versionNo").value(2));

        ingest("handbook", "\"classification\":\"confidential\"").andExpect(jsonPath("$.unchanged").value(1));
    }

    @Test
    void rejectsLabelsOutsideTheSchema() throws Exception {
        submit("doc", "\"classification\":\"secret\"").andExpect(status().isBadRequest());
        submit("doc", "\"allowedDepartments\":[\"\"]").andExpect(status().isBadRequest());
    }

    /**
     * The document keys a principal can reach, which must be the same through the listing, both channels and the fused strategy.
     */
    private List<String> visibleTo(Map<String, Object> claims) throws Exception {
        String token = DemoTokens.sign(claims, "grounded-access-demo");
        String listing = mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
        List<String> listed = JsonPath.<List<String>>read(listing, "$.chunks[*].documentKey").stream().sorted().toList();
        for (String strategy : List.of("sparse-only", "dense-only", "hybrid-rrf")) {
            String body = search(claims, strategy).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> found = JsonPath.<List<String>>read(body, "$.results[*].documentKey").stream().sorted().toList();
            assertThat(found).as(strategy).isEqualTo(listed);
        }
        return listed;
    }

    private List<String> chunkIds(Map<String, Object> claims) throws Exception {
        String token = DemoTokens.sign(claims, "grounded-access-demo");
        String listing = mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(listing, "$.chunks[*].chunkId");
    }

    private ResultActions search(Map<String, Object> claims, String strategy) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + DemoTokens.sign(claims, "grounded-access-demo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"company handbook policy\",\"strategy\":\"%s\",\"k\":50}".formatted(strategy)));
    }

    private static Map<String, Object> principal(Object... claims) {
        Map<String, Object> all = new HashMap<>(Map.of("sub", "reader", "tenant_id", "northstar", "scope", "query debug"));
        for (int i = 0; i < claims.length; i += 2) {
            all.put((String) claims[i], claims[i + 1]);
        }
        return all;
    }

    private ResultActions submit(String key, String labels) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\",\"content\":\"# Handbook\\n\\nThe company handbook policy for %s.\\n\",%s}]}"
                .formatted(key, key, key.replace("-doc", ""), labels);
        return mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ingest(String key, String labels) throws Exception {
        String location = submit(key, labels).andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
        worker.drain();
        return mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$.status").value("succeeded"));
    }
}

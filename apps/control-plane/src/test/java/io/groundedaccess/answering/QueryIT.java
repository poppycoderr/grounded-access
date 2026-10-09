package io.groundedaccess.answering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.WordOverlapRerankClient;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.ChatClient;
import io.groundedaccess.modelclient.ChatReply;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.RerankClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
@Import({TestcontainersConfiguration.class, QueryIT.FakeModels.class})
class QueryIT {

    private static final String READER = DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query", "clearance", "internal"),
            "grounded-access-demo");

    /** What the chat model does next: null means no chat model is configured, "FAIL" makes the call fail, anything else is its reply. */
    private static final AtomicReference<String> CHAT = new AtomicReference<>();

    private static final List<String> PROMPTS = Collections.synchronizedList(new ArrayList<>());

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }

        @Bean
        @Primary
        RerankClient wordOverlapRerankClient() {
            return new WordOverlapRerankClient();
        }

        @Bean
        @Primary
        ChatClient scriptedChatClient() {
            return new ChatClient() {

                @Override
                public boolean isConfigured() {
                    return CHAT.get() != null;
                }

                @Override
                public ChatReply complete(String systemPrompt, String userPrompt) {
                    PROMPTS.add(systemPrompt + "\n" + userPrompt);
                    if ("FAIL".equals(CHAT.get())) {
                        throw new ResourceAccessException("chat model unavailable");
                    }
                    return new ChatReply("scripted-model", CHAT.get());
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
        CHAT.set(null);
        PROMPTS.clear();
        ingest("hr-volunteer-policy", "EU employees receive two paid volunteer days per calendar year.", "internal");
        ingest("hr-travel-policy", "The meal allowance is 60 EUR per day for travel inside the EU.", "internal");
        ingest("hr-compensation-bands", "Staff engineers receive two hundred paid volunteer days as a secret bonus.", "confidential");
    }

    @Test
    void withoutAChatModelTheEvidenceIsReturnedAlone() throws Exception {
        query("How many paid volunteer days do EU employees receive?").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("evidence_only"))
                .andExpect(jsonPath("$.statements", empty()))
                .andExpect(jsonPath("$.degraded", empty()))
                .andExpect(jsonPath("$.evidence[0].id").value("S1"))
                .andExpect(jsonPath("$.evidence[0].documentKey").value("hr-volunteer-policy"))
                .andExpect(jsonPath("$.evidence[*].documentKey").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("hr-compensation-bands"))));
        assertThat(PROMPTS).isEmpty();
    }

    @Test
    void anAnswerCarriesOnlyValidatedStatementsAndTheEvidenceTheyCite() throws Exception {
        CHAT.set("""
                {"answerable": true, "statements": [
                  {"text": "EU employees receive two paid volunteer days per year.", "citations": ["S1"]},
                  {"text": "They also receive a company car.", "citations": ["S9"]}]}""");

        query("How many paid volunteer days do EU employees receive?").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.statements", hasSize(1)))
                .andExpect(jsonPath("$.statements[0].text").value("EU employees receive two paid volunteer days per year."))
                .andExpect(jsonPath("$.statements[0].citations", contains("S1")))
                .andExpect(jsonPath("$.evidence", hasSize(1)))
                .andExpect(jsonPath("$.evidence[0].id").value("S1"))
                .andExpect(jsonPath("$.evidence[0].documentKey").value("hr-volunteer-policy"))
                .andExpect(jsonPath("$.evidence[0].versionNo").value(1));

        Map<String, Object> event = jdbc.sql("select attributes::text as attributes from audit_event where action = 'query.answer'").query().singleRow();
        assertThat(String.valueOf(event.get("attributes"))).contains("\"status\": \"answered\"", "\"statementCount\": 1", "\"rejectedStatements\": 1",
                "\"chatModel\": \"scripted-model\"", "\"promptVersion\": \"answer-prompt/1\"").doesNotContain("volunteer", "company car");
    }

    @Test
    void theChatModelOnlyEverSeesEvidenceThePrincipalMayRead() throws Exception {
        CHAT.set("{\"answerable\": false, \"statements\": []}");

        query("How many paid volunteer days do staff engineers receive?").andExpect(status().isOk());

        assertThat(PROMPTS).hasSize(1);
        assertThat(PROMPTS.getFirst()).contains("<evidence id=\"S1\">", "two paid volunteer days per calendar year").doesNotContain("secret bonus", "two hundred");
    }

    @Test
    void aRefusalAndAnAnswerWithNoValidStatementAreTheSameNoAnswerWithoutEvidence() throws Exception {
        CHAT.set("{\"answerable\": false, \"statements\": []}");
        String refused = body(query("What is the capital of France?"));
        CHAT.set("{\"answerable\": true, \"statements\": [{\"text\": \"Paris.\", \"citations\": [\"S42\"]}]}");
        String unsupported = body(query("What is the capital of France?"));

        assertThat(refused).contains("\"status\":\"no_answer\"", "\"statements\":[]", "\"evidence\":[]").isEqualTo(unsupported);
    }

    @Test
    void aFailingOrMalformedGenerationFallsBackToEvidenceAndSaysSo() throws Exception {
        CHAT.set("FAIL");
        query("How many paid volunteer days do EU employees receive?").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("evidence_only"))
                .andExpect(jsonPath("$.degraded", contains("generation_unavailable")))
                .andExpect(jsonPath("$.evidence[0].documentKey").value("hr-volunteer-policy"));

        CHAT.set("Sure! The answer is two days.");
        query("How many paid volunteer days do EU employees receive?").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("evidence_only"))
                .andExpect(jsonPath("$.degraded", contains("generation_invalid")))
                .andExpect(jsonPath("$.statements", empty()));
    }

    @Test
    void aPrincipalWithNothingToReadGetsNoAnswerAndTheChatModelIsNotCalled() throws Exception {
        CHAT.set("{\"answerable\": true, \"statements\": [{\"text\": \"Leaked.\", \"citations\": [\"S1\"]}]}");
        String outsider = DemoTokens.token("mallory", "external", "query");

        mvc.perform(post("/api/v1/query").header("Authorization", "Bearer " + outsider).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"How many paid volunteer days do EU employees receive?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_answer"))
                .andExpect(jsonPath("$.evidence", empty()));
        assertThat(PROMPTS).isEmpty();
    }

    @Test
    void queryNeedsTheQueryScope() throws Exception {
        mvc.perform(post("/api/v1/query").header("Authorization", "Bearer " + DemoTokens.token("admin", "northstar", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")).andExpect(status().isUnauthorized());
    }

    private ResultActions query(String question) throws Exception {
        return mvc.perform(post("/api/v1/query").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"asOf\":\"2026-06-01T00:00:00Z\"}".formatted(question)));
    }

    private static String body(ResultActions response) throws Exception {
        return response.andReturn().getResponse().getContentAsString().replaceAll("\"executionId\":\"[^\"]+\"", "").replaceAll("\"traceId\":\"[^\"]+\"", "");
    }

    private void ingest(String key, String sentence, String classification) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"%s\",\"content\":\"# Policy\\n\\n%s\\n\",\"classification\":\"%s\"}]}"
                .formatted(key, key, sentence, classification);
        mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + DemoTokens.token("admin", "northstar", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        worker.drain();
    }
}

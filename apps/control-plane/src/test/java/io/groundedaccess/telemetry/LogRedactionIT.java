package io.groundedaccess.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.api.TraceFilter;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.ChatClient;
import io.groundedaccess.modelclient.ChatReply;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;
import io.groundedaccess.modelclient.RerankClient;
import io.groundedaccess.modelclient.RerankScores;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpClientErrorException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every model call fails here the way a real one can: with an error whose message quotes the text that was sent. The log must say what
 * failed without repeating it.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.ingestion.worker.max-attempts=1",
        "logging.structured.format.console=ecs"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LogRedactionIT.Fakes.class})
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String READER = DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query debug", "clearance", "internal"),
            "grounded-access-demo");

    private static final String QUESTION = "How many zebraquestion volunteer days do employees receive?";

    /** Text that must never be logged: the question, a document title, document content, the prompt. */
    private static final List<String> FORBIDDEN = List.of("zebraquestion", "Zebratitle", "zebracontent", "Answer the question");

    private static final AtomicBoolean EMBEDDING_REJECTS = new AtomicBoolean();

    private static final AtomicBoolean RERANKER_REJECTS = new AtomicBoolean();

    private static HttpClientErrorException rejected(Object sent) {
        byte[] body = ("{\"detail\":\"" + sent + "\"}").getBytes(StandardCharsets.UTF_8);
        // RestClient puts the response body into the exception message, which is how the text would reach a log line.
        return HttpClientErrorException.create("422 Unprocessable: \"" + sent + "\"", HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable", HttpHeaders.EMPTY, body,
                StandardCharsets.UTF_8);
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        EmbeddingClient rejectingEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    if (EMBEDDING_REJECTS.get()) {
                        throw rejected(texts);
                    }
                    return super.embed(texts, inputType);
                }
            };
        }

        @Bean
        @Primary
        RerankClient rejectingRerankClient() {
            return new RerankClient() {

                @Override
                public String modelName() {
                    return "test-reranker";
                }

                @Override
                public RerankScores score(String query, List<String> passages) {
                    if (RERANKER_REJECTS.get()) {
                        throw rejected(query + " " + passages);
                    }
                    return new RerankScores("test-reranker", "test", passages.stream().map(passage -> 1.0).toList());
                }
            };
        }

        @Bean
        @Primary
        ChatClient rejectingChatClient() {
            return new ChatClient() {

                @Override
                public boolean isConfigured() {
                    return true;
                }

                @Override
                public ChatReply complete(String systemPrompt, String userPrompt) {
                    throw rejected(systemPrompt + " " + userPrompt);
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
        EMBEDDING_REJECTS.set(false);
        RERANKER_REJECTS.set(false);
    }

    @AfterEach
    void restoreAudit() {
        jdbc.sql("alter table if exists audit_event_broken rename to audit_event").update();
    }

    @Test
    void failuresAreLoggedWithoutTheTextThatCausedThem(CapturedOutput output) throws Exception {
        ingest("hr-volunteer-policy");
        EMBEDDING_REJECTS.set(true);
        ingest("hr-rejected-policy");
        search("dense-only").andExpect(status().isServiceUnavailable());
        EMBEDDING_REJECTS.set(false);
        RERANKER_REJECTS.set(true);
        String traceId = search("hybrid-rrf-rerank").andExpect(status().isOk()).andReturn().getResponse().getHeader(TraceFilter.HEADER);
        mvc.perform(post("/api/v1/query").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\"}".formatted(QUESTION))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/query").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\"".formatted(QUESTION))).andExpect(status().isBadRequest());
        search("no-such-strategy " + QUESTION).andExpect(status().isBadRequest());
        jdbc.sql("alter table audit_event rename to audit_event_broken").update();
        search("sparse-only").andExpect(status().isServiceUnavailable());

        String log = output.getAll();

        assertThat(log).contains("failed on attempt 1 with MODEL_SERVICE_REJECTED: UnprocessableContent status=422")
                .contains("Model service call failed: UnprocessableContent status=422")
                .contains("Reranking failed, answering in the fused order: UnprocessableContent status=422")
                .contains("Generation failed, returning evidence only: UnprocessableContent status=422")
                .contains("Audit write failed, request refused: BadSqlGrammarException sqlState=42P01");
        assertThat(log).doesNotContain(FORBIDDEN);
        JsonNode reranking = log.lines()
                .filter(line -> line.contains("Reranking failed"))
                .map(line -> new ObjectMapper().readTree(line))
                .findFirst()
                .orElseThrow();
        assertThat(reranking.path("traceId").asString()).as("a log line carries the trace id of its request").isEqualTo(traceId);
    }

    private void ingest(String key) throws Exception {
        String body = "{\"documents\":[{\"key\":\"%s\",\"title\":\"Zebratitle Policy\",\"content\":\"# Zebratitle Policy\\n\\n"
                + "Employees receive two paid volunteer days per zebracontent year.\\n\",\"classification\":\"internal\"}]}";
        mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON).content(body.formatted(key)))
                .andExpect(status().isAccepted());
        worker.drain();
    }

    private ResultActions search(String strategy) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"strategy\":\"%s\"}".formatted(QUESTION, strategy)));
    }
}

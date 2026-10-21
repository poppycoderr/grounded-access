package io.groundedaccess.answering;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.authorization.Classification;
import io.groundedaccess.corpus.DocumentFormat;
import io.groundedaccess.corpus.DocumentScope;
import io.groundedaccess.corpus.IngestionService;
import io.groundedaccess.corpus.SourceDocument;
import io.groundedaccess.modelclient.ChatClient;
import io.groundedaccess.modelclient.ChatReply;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * When a revocation takes effect for a request already running (ADR-0011). Each retrieval statement applies the authorization predicate as of
 * its own start; a request already running is not re-checked. These tests pin that behaviour, so a change to it is a deliberate decision.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RevocationBoundaryIT.Models.class})
class RevocationBoundaryIT {

    private static final String READER = DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query", "clearance", "internal"),
            "grounded-access-demo");

    /** Runs once, at the moment named by the test: between the two retrieval channels, or while the chat model generates. */
    private static final AtomicReference<Runnable> DURING_QUERY_EMBEDDING = new AtomicReference<>();

    private static final AtomicReference<Runnable> DURING_GENERATION = new AtomicReference<>();

    @TestConfiguration
    static class Models {

        @Bean
        @Primary
        EmbeddingClient hookedEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    Runnable hook = inputType == InputType.QUERY ? DURING_QUERY_EMBEDDING.getAndSet(null) : null;
                    if (hook != null) {
                        hook.run();
                    }
                    return super.embed(texts, inputType);
                }
            };
        }

        @Bean
        @Primary
        ChatClient hookedChatClient() {
            return new ChatClient() {

                @Override
                public boolean isConfigured() {
                    return true;
                }

                @Override
                public ChatReply complete(String systemPrompt, String userPrompt) {
                    Runnable hook = DURING_GENERATION.getAndSet(null);
                    if (hook != null) {
                        hook.run();
                    }
                    return new ChatReply("scripted-model", """
                            {"answerable": true, "statements": [{"text": "Employees receive two paid volunteer days.", "citations": ["S1"]}]}""");
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionService ingestion;

    @BeforeEach
    void corpus() {
        jdbc.sql("truncate audit_event, query_execution, chunk, document_version, document, tenant cascade").update();
        DURING_QUERY_EMBEDDING.set(null);
        DURING_GENERATION.set(null);
        ingest(Classification.INTERNAL);
    }

    @Test
    void aRevocationBetweenTheTwoChannelsOfASearchLetsRowsOfTheEarlierStatementThrough() throws Exception {
        // The sparse statement runs before the query is embedded, the dense statement after it
        DURING_QUERY_EMBEDDING.set(() -> ingest(Classification.CONFIDENTIAL));

        search().andExpect(status().isOk()).andExpect(jsonPath("$.results[*].documentKey", hasItem("handbook")));

        search().andExpect(jsonPath("$.results[*].documentKey", not(hasItem("handbook"))));
    }

    @Test
    void aRevocationWhileAnAnswerIsGeneratedDoesNotRecallTheEvidenceAlreadyRetrieved() throws Exception {
        DURING_GENERATION.set(() -> ingest(Classification.CONFIDENTIAL));

        query().andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.evidence[*].documentKey", hasItem("handbook")));

        query().andExpect(jsonPath("$.evidence[*].documentKey", not(hasItem("handbook"))));
    }

    private void ingest(Classification classification) {
        ingestion.ingest("northstar", List.of(new SourceDocument("handbook", "Handbook", null, "Employees receive two paid volunteer days.",
                DocumentFormat.TEXT, new AccessLabels(classification, Set.of(), Set.of()), DocumentScope.EVERYWHERE_ALWAYS)));
    }

    private ResultActions search() throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"paid volunteer days\",\"strategy\":\"hybrid-rrf\"}"));
    }

    private ResultActions query() throws Exception {
        return mvc.perform(post("/api/v1/query").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"How many paid volunteer days do employees receive?\"}"));
    }
}

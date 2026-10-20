package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;
import io.groundedaccess.modelclient.TextTooLongException;

import java.util.List;
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

/**
 * Long inputs (ADR-0009): a document of any length is chunked into inputs the models accept, a query that is too long is the caller's error, and
 * a model service that still refuses a chunk is reported as a broken contract.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LongInputIT.Models.class})
class LongInputIT {

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String READER = DemoTokens.token("reader", "northstar", "query");

    /** Words in an input above which the stand-in model service refuses it, as the real one refuses over 512 tokens. */
    private static final int REFUSED_ABOVE_WORDS = 510;

    private static final AtomicBoolean REFUSE_EVERY_PASSAGE = new AtomicBoolean();

    @TestConfiguration
    static class Models {

        @Bean
        @Primary
        EmbeddingClient refusingEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    boolean probe = texts.equals(List.of(IndexProfiles.PROBE));
                    if (!probe && (texts.stream().anyMatch(t -> t.split("\\s+").length > REFUSED_ABOVE_WORDS)
                            || (REFUSE_EVERY_PASSAGE.get() && inputType == InputType.PASSAGE))) {
                        throw new TextTooLongException("text 0 has 600 tokens; the model accepts at most 512 per text");
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
    private IngestionService ingestion;

    @Autowired
    private IngestionWorker worker;

    @BeforeEach
    void reset() {
        jdbc.sql("truncate ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        REFUSE_EVERY_PASSAGE.set(false);
    }

    @Test
    void aTenThousandCharacterSentenceIsChunkedIntoInputsTheModelsAccept() {
        String sentence = "Volunteer " + "days ".repeat(2_000) + "end.";

        ingestion.ingest("northstar", List.of(document("long", sentence)));

        List<String> chunks = jdbc.sql("select content from chunk order by ordinal").query(String.class).list();
        assertThat(chunks).hasSizeGreaterThan(5)
                .allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(DocumentChunker.MAX_CHARS))
                .allSatisfy(c -> assertThat(c.split("\\s+").length + 2).isLessThanOrEqualTo(DocumentChunker.MAX_TOKENS));
        assertThat(String.join(" ", chunks).replaceAll("\\s+", " ")).isEqualTo(sentence);
    }

    @Test
    void aQueryLongerThanTheModelAcceptsIsTheCallersErrorForEveryStrategyWithADenseChannel() throws Exception {
        ingestion.ingest("northstar", List.of(document("handbook", "Employees receive two paid volunteer days.")));
        String query = "a ".repeat(600).strip();

        for (String strategy : List.of("dense-only", "hybrid-rrf", "hybrid-rrf-rerank")) {
            search(query, strategy).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUERY_TOO_LONG"));
        }
        search(query, "sparse-only").andExpect(status().isOk());
    }

    @Test
    void aChunkTheModelServiceStillRefusesFailsTheJobAsABrokenContractWithoutARetry() throws Exception {
        REFUSE_EVERY_PASSAGE.set(true);
        String location = mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"documents":[{"key":"handbook","title":"Handbook","content":"Employees receive two paid volunteer days.","format":"text"}]}
                        """)).andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
        worker.drain();

        mvc.perform(get(String.valueOf(location)).header("Authorization", "Bearer " + ADMIN))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.errorCode").value("MODEL_RESPONSE_INVALID"))
                .andExpect(jsonPath("$.attempts").value(1));
        assertThat(jdbc.sql("select count(*) from document_version").query(Integer.class).single()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions search(String query, String strategy) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"strategy\":\"%s\"}".formatted(query, strategy)));
    }

    private static SourceDocument document(String key, String content) {
        return new SourceDocument(key, "Handbook", null, content, DocumentFormat.TEXT, AccessLabels.UNRESTRICTED, DocumentScope.EVERYWHERE_ALWAYS);
    }
}

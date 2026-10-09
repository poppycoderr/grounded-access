package io.groundedaccess.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.api.TraceFilter;
import io.groundedaccess.ingestion.IngestionWorker;
import io.groundedaccess.modelclient.ChatClient;
import io.groundedaccess.modelclient.ChatReply;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.RerankClient;
import io.groundedaccess.modelclient.RerankScores;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
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

@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TracingIT.Fakes.class})
class TracingIT {

    private static final InMemorySpanExporter EXPORTED = InMemorySpanExporter.create();

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    private static final String READER = DemoTokens.sign(Map.of("sub", "alice", "tenant_id", "northstar", "scope", "query debug", "clearance", "internal"),
            "grounded-access-demo");

    private static final String QUESTION = "How many zebraquestion volunteer days do employees receive?";

    private static final String TITLE = "Zebratitle Volunteer Policy";

    private static final String CONTENT = "Employees receive two paid volunteer days per zebracontent year.";

    private static final String REPLY = """
            {"answerable": true, "statements": [{"text": "Employees receive two zebrareply days.", "citations": ["S1"]}]}""";

    /** Text that must never leave the process in a span: the question, a document title, document content, the model's reply. */
    private static final List<String> FORBIDDEN = List.of("zebraquestion", "Zebratitle", "zebracontent", "zebrareply");

    /** While true, the reranker rejects the request with an error whose message quotes what it was sent, as a real model service does. */
    private static final AtomicBoolean RERANKER_REJECTS = new AtomicBoolean();

    @TestConfiguration
    static class Fakes {

        @Bean
        SpanExporter inMemorySpanExporter() {
            return EXPORTED;
        }

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }

        @Bean
        @Primary
        RerankClient echoingRerankClient() {
            return new RerankClient() {

                @Override
                public String modelName() {
                    return "test-reranker";
                }

                @Override
                public RerankScores score(String query, List<String> passages) {
                    if (RERANKER_REJECTS.get()) {
                        byte[] body = ("{\"detail\":\"" + query + " " + passages + "\"}").getBytes(StandardCharsets.UTF_8);
                        throw HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable", HttpHeaders.EMPTY, body, StandardCharsets.UTF_8);
                    }
                    return new RerankScores("test-reranker", "test", passages.stream().map(passage -> 1.0).toList());
                }
            };
        }

        @Bean
        @Primary
        ChatClient scriptedChatClient() {
            return new ChatClient() {

                @Override
                public boolean isConfigured() {
                    return true;
                }

                @Override
                public ChatReply complete(String systemPrompt, String userPrompt) {
                    return new ChatReply("scripted-model", REPLY);
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

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void corpus() throws Exception {
        jdbc.sql("truncate audit_event, query_execution, ingestion_job_document, ingestion_job, chunk, document_version, document, tenant cascade").update();
        RERANKER_REJECTS.set(false);
        String body = "{\"documents\":[{\"key\":\"hr-volunteer-policy\",\"title\":\"%s\",\"content\":\"# %s\\n\\n%s\\n\",\"classification\":\"internal\"}]}"
                .formatted(TITLE, TITLE, CONTENT);
        mvc.perform(post("/api/v1/ingestion-jobs").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        worker.drain();
        exported();
        EXPORTED.reset();
    }

    @Test
    void oneTraceIdCoversEveryStageOfAQuery() throws Exception {
        String traceId = query().andExpect(status().isOk()).andReturn().getResponse().getHeader(TraceFilter.HEADER);

        List<SpanData> spans = exported();

        assertThat(spans).isNotEmpty().allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(traceId));
        Map<String, SpanData> byName = spans.stream().collect(Collectors.toMap(SpanData::getName, Function.identity()));
        Map<String, String> parents = spans.stream()
                .filter(span -> byName.values().stream().anyMatch(parent -> parent.getSpanId().equals(span.getParentSpanId())))
                .collect(Collectors.toMap(SpanData::getName, span -> spans.stream()
                        .filter(parent -> parent.getSpanId().equals(span.getParentSpanId()))
                        .findFirst()
                        .orElseThrow()
                        .getName()));
        assertThat(parents).containsEntry("retrieval.search", "query.answer")
                .containsEntry("policy.compile", "retrieval.search")
                .containsEntry("retrieval.sparse", "retrieval.search")
                .containsEntry("retrieval.dense", "retrieval.search")
                .containsEntry("model.embed", "retrieval.dense")
                .containsEntry("retrieval.fusion", "retrieval.search")
                .containsEntry("rerank", "retrieval.search")
                .containsEntry("model.rerank", "rerank")
                .containsEntry("context.build", "query.answer")
                .containsEntry("generation", "query.answer")
                .containsEntry("citation.validate", "query.answer");
        assertThat(parents.get("query.answer")).startsWith("http post");
        assertThat(attributes(byName.get("retrieval.search"))).containsEntry("retrieval.strategy", "hybrid-rrf-rerank")
                .containsEntry("policy.version", "abac/1")
                .containsEntry("retrieval.results", "1")
                .containsKey("pipeline.config_hash");
        assertThat(attributes(byName.get("model.rerank"))).containsEntry("model.name", "test-reranker@test");
        assertThat(attributes(byName.get("query.answer"))).containsEntry("answer.status", "answered");
    }

    @Test
    void noSpanCarriesTheQuestionADocumentOrAModelReply() throws Exception {
        query().andExpect(status().isOk());
        for (String strategy : List.of("sparse-only", "dense-only", "hybrid-rrf", "hybrid-rrf-rerank")) {
            search(strategy).andExpect(status().isOk());
        }
        RERANKER_REJECTS.set(true);
        search("hybrid-rrf-rerank").andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents/hr-volunteer-policy").header("Authorization", "Bearer " + READER)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents/" + FORBIDDEN.getFirst()).header("Authorization", "Bearer " + READER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + READER)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"strategy\":\"unknown\"}".formatted(QUESTION))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"%s\"}".formatted(QUESTION)))
                .andExpect(status().isUnauthorized());

        List<SpanData> spans = exported();

        assertThat(spans).extracting(SpanData::getName).contains("query.answer", "retrieval.search", "generation", "model.rerank");
        assertThat(spans).anySatisfy(span -> assertThat(attributes(span)).containsEntry("error.type", "UnprocessableContent"));
        assertThat(spans).allSatisfy(span -> {
            assertThat(span.getAttributes().asMap().keySet()).allSatisfy(key -> assertThat(SpanAttribute.isAllowed(key.getKey())).as(key.getKey()).isTrue());
            assertThat(span.getEvents()).isEmpty();
            assertThat(span.getStatus().getDescription()).isEmpty();
            assertThat(span.getName() + " " + attributes(span)).doesNotContain(FORBIDDEN);
        });
    }

    @Test
    void aMalformedTraceHeaderIsReplacedAndAValidOneIsKept() throws Exception {
        for (String header : List.of("00-zebraquestion-00f067aa0ba902b7-01", "zebraquestion", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01")) {
            String traceId = mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + READER).header("traceparent", header))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getHeader(TraceFilter.HEADER);
            assertThat(traceId).as(header).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
            assertThat(jdbc.sql("select count(*) from audit_event where trace_id = :id").param("id", traceId).query(Integer.class).single()).isEqualTo(1);
        }
        String given = "4bf92f3577b34da6a3ce929d0e0e4736";
        mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + READER).header("traceparent", "00-" + given + "-00f067aa0ba902b7-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceFilter.HEADER, given));

        assertThat(exported()).isNotEmpty().allSatisfy(span -> assertThat(span.getTraceId()).matches("[0-9a-f]{32}"));
        assertThat(exported()).extracting(SpanData::getTraceId).contains(given);
    }

    @Test
    void nothingIsSentToACollectorUnlessOneIsConfigured() {
        assertThat(context.getBeanNamesForType(OtlpMeterRegistry.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OtlpGrpcSpanExporter.class)).isEmpty();
    }

    @Test
    void healthProbesAreNotTraced() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        assertThat(exported()).isEmpty();
    }

    private List<SpanData> exported() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        return EXPORTED.getFinishedSpanItems();
    }

    private static Map<String, String> attributes(SpanData span) {
        return span.getAttributes().asMap().entrySet().stream().collect(Collectors.toMap(e -> e.getKey().getKey(), e -> String.valueOf(e.getValue())));
    }

    private ResultActions query() throws Exception {
        return mvc.perform(post("/api/v1/query").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\"}".formatted(QUESTION)));
    }

    private ResultActions search(String strategy) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"%s\",\"strategy\":\"%s\"}".formatted(QUESTION, strategy)));
    }
}

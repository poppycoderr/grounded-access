package io.groundedaccess.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.web.client.ResourceAccessException;

/**
 * The model service was down when the control plane started, so the index profile is not established yet (ADR-0007). The searches after it
 * comes back must stay within their deadline: establishing the profile happens once, in the background, never on a search's time
 * (review 2026-10-21, N1).
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.retrieval.deadline=300ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ColdProfileIT.Models.class})
class ColdProfileIT {

    private static final String READER = DemoTokens.token("reader", "northstar", "query");

    /** Down at startup; the test brings it back. */
    private static final AtomicBoolean DOWN = new AtomicBoolean(true);

    private static final AtomicInteger PROBES = new AtomicInteger();

    @TestConfiguration
    static class Models {

        @Bean
        @Primary
        EmbeddingClient slowProbeEmbeddingClient() {
            return new HashingEmbeddingClient() {

                @Override
                public Embeddings embed(List<String> texts, InputType inputType) {
                    if (DOWN.get()) {
                        throw new ResourceAccessException("model service unavailable");
                    }
                    if (texts.equals(List.of(IndexProfiles.PROBE))) {
                        PROBES.incrementAndGet();
                        if (inputType == InputType.PASSAGE) {
                            sleep(Duration.ofMillis(1500));
                        }
                    }
                    return super.embed(texts, inputType);
                }

                @Override
                public Duration timeout(InputType inputType) {
                    return Duration.ofMillis(100);
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IndexProfiles profiles;

    @Test
    void searchesAfterTheModelServiceReturnsStayWithinTheirDeadlineWhileTheProfileIsEstablishedOnce() throws Exception {
        jdbc.sql("insert into tenant (id, name) values ('northstar', 'northstar') on conflict do nothing").update();
        DOWN.set(false);

        List<Callable<long[]>> searches = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            searches.add(() -> {
                long started = System.nanoTime();
                String body = mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"paid volunteer days\",\"strategy\":\"hybrid-rrf\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
                List<String> degraded = JsonPath.read(body, "$.degraded");
                return new long[] {(System.nanoTime() - started) / 1_000_000, degraded.contains("dense_unavailable") ? 1 : 0};
            });
        }
        List<long[]> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (Future<long[]> result : pool.invokeAll(searches)) {
                results.add(result.get());
            }
        }

        assertThat(results).as("every search returned within the 300 ms deadline plus database time, answered by the sparse channel")
                .allSatisfy(r -> assertThat(r[0]).isLessThan(600))
                .allSatisfy(r -> assertThat(r[1]).isOne());
        long waited = System.nanoTime();
        while (profiles.current().isEmpty() && System.nanoTime() - waited < Duration.ofSeconds(5).toNanos()) {
            sleep(Duration.ofMillis(50));
        }
        assertThat(PROBES.get()).as("one query probe and one passage probe, however many searches asked").isEqualTo(2);
        String body = mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"paid volunteer days\",\"strategy\":\"hybrid-rrf\"}")).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(body, "$.degraded")).as("dense search once the profile is established").isEmpty();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

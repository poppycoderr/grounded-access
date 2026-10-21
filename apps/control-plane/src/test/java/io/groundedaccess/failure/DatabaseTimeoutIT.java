package io.groundedaccess.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.corpus.DocumentFormat;
import io.groundedaccess.corpus.DocumentScope;
import io.groundedaccess.corpus.IngestionService;
import io.groundedaccess.corpus.SourceDocument;
import io.groundedaccess.modelclient.EmbeddingClient;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

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
 * Retrieval SQL has its own time limit (review 2026-10-21, D3). A query that cannot finish in time, here because another transaction holds
 * the chunk table, fails as a database timeout instead of holding the request and its connection without bound, and it is never reported as
 * a model failure.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.retrieval.sql-timeout=1s",
        "spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DatabaseTimeoutIT.Models.class})
class DatabaseTimeoutIT {

    private static final String READER = DemoTokens.token("reader", "northstar", "query debug");

    @TestConfiguration
    static class Models {

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
    private DataSource dataSource;

    @Autowired
    private IngestionService ingestion;

    @BeforeEach
    void corpus() {
        jdbc.sql("truncate chunk, document_version, document, tenant cascade").update();
        ingestion.ingest("northstar", List.of(new SourceDocument("handbook", "Handbook", null, "Employees receive two paid volunteer days.",
                DocumentFormat.TEXT, AccessLabels.UNRESTRICTED, DocumentScope.EVERYWHERE_ALWAYS)));
    }

    @Test
    void retrievalSqlThatCannotFinishInTimeFailsAsADatabaseTimeout() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("lock table chunk in access exclusive mode");
            }
            for (String strategy : List.of("sparse-only", "hybrid-rrf")) {
                long started = System.nanoTime();
                search(strategy).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DATABASE_TIMEOUT"));
                assertThat(Duration.ofNanos(System.nanoTime() - started)).as(strategy).isLessThan(Duration.ofMillis(2500));
            }
            long started = System.nanoTime();
            mvc.perform(get("/api/v1/retrieval/chunks").header("Authorization", "Bearer " + READER))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DATABASE_TIMEOUT"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
            holder.rollback();
        }
        search("hybrid-rrf").andExpect(status().isOk()).andExpect(jsonPath("$.degraded").isEmpty());
    }

    @Test
    void aRequestThatGetsNoConnectionInTimeFailsAsTheDatabaseBeingUnavailable() throws Exception {
        List<Connection> held = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                held.add(dataSource.getConnection());
            }
            long started = System.nanoTime();
            search("sparse-only").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
        search("sparse-only").andExpect(status().isOk());
    }

    private ResultActions search(String strategy) throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"paid volunteer days\",\"strategy\":\"%s\"}".formatted(strategy)));
    }
}

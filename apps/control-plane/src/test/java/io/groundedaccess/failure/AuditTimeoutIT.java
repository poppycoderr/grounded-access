package io.groundedaccess.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 * Audit writes have their own time limit. An audit write that cannot finish in time, here because another transaction holds the audit table,
 * fails the request closed, as any other audit failure does: nothing is returned and nothing the audit was meant to record is kept.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.audit.sql-timeout=1s"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuditTimeoutIT.Models.class})
class AuditTimeoutIT {

    private static final String READER = DemoTokens.token("reader", "northstar", "query");

    private static final String ADMIN = DemoTokens.token("admin", "northstar", "admin");

    /** Without a time limit the request would wait for the lock; it is released after this, so a failing run ends. */
    private static final Duration LOCK_HELD = Duration.ofSeconds(6);

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
        jdbc.sql("truncate chunk, document_version, document, tenant, query_execution, audit_event cascade").update();
        ingestion.ingest("northstar", List.of(new SourceDocument("handbook", "Handbook", null, "Employees receive two paid volunteer days.",
                DocumentFormat.TEXT, AccessLabels.UNRESTRICTED, DocumentScope.EVERYWHERE_ALWAYS)));
    }

    @Test
    void anAuditWriteThatCannotFinishInTimeFailsTheSearchClosed() throws Exception {
        whileTheAuditTableIsLocked(() -> {
            long started = System.nanoTime();
            search().andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                    .andExpect(jsonPath("$.results").doesNotExist());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
        });

        assertThat(jdbc.sql("select count(*) from query_execution").query(Integer.class).single())
                .as("the execution record is written with its audit event or not at all")
                .isZero();
        search().andExpect(status().isOk());
    }

    @Test
    void aDocumentChangeWhoseAuditCannotFinishInTimeIsNotApplied() throws Exception {
        whileTheAuditTableIsLocked(() -> {
            long started = System.nanoTime();
            mvc.perform(patch("/api/v1/documents/handbook").header("Authorization", "Bearer " + ADMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"disabled\"}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
        });

        assertThat(jdbc.sql("select status from document where external_key = 'handbook'").query(String.class).single()).isEqualTo("active");
    }

    @FunctionalInterface
    private interface Check {

        void run() throws Exception;
    }

    private void whileTheAuditTableIsLocked(Check check) throws Exception {
        ScheduledExecutorService release = Executors.newSingleThreadScheduledExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("lock table audit_event in access exclusive mode");
            }
            release.schedule(() -> {
                holder.rollback();
                return null;
            }, LOCK_HELD.toMillis(), TimeUnit.MILLISECONDS);
            check.run();
        } finally {
            release.shutdownNow();
        }
    }

    private ResultActions search() throws Exception {
        return mvc.perform(post("/api/v1/retrieval/search").header("Authorization", "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"paid volunteer days\",\"strategy\":\"sparse-only\"}"));
    }
}

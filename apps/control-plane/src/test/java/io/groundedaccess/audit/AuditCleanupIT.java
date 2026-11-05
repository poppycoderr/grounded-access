package io.groundedaccess.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.modelclient.EmbeddingClient;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cleanup removes audit rows only when they are past their table's retention and covered by a recorded export, in bounded batches that are
 * each audited, and never waits for a row another transaction holds (ADR-0013).
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.audit.retention.cleanup.enabled=false"})
@Import({TestcontainersConfiguration.class, AuditCleanupIT.Models.class})
class AuditCleanupIT {

    @TestConfiguration
    static class Models {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }
    }

    private static final Duration THIRTY_DAYS = Duration.ofDays(30);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AuditTrail trail;

    private final Instant now = Instant.now();

    @BeforeEach
    void rows() {
        jdbc.sql("truncate audit_event, query_execution, audit_export").update();
    }

    @Test
    void withoutARetentionNothingIsDeleted() {
        event("ancient", now.minus(Duration.ofDays(3650)));
        exported(now.minus(Duration.ofDays(1)));

        assertThat(cleanup(null, null, true, 100).purge()).isZero();
        assertThat(actions()).containsExactly("ancient");
    }

    @Test
    void withoutAnExportNothingIsDeletedWhateverTheRetention() {
        event("old", now.minus(Duration.ofDays(90)));

        assertThat(cleanup(THIRTY_DAYS, THIRTY_DAYS, true, 100).purge()).isZero();
        assertThat(actions()).containsExactly("old");
    }

    @Test
    void onlyRowsPastTheirRetentionAndCoveredByTheLatestExportAreDeleted() {
        event("old-and-exported", now.minus(Duration.ofDays(90)));
        event("old-not-exported", now.minus(Duration.ofDays(50)));
        event("recent", now.minus(Duration.ofDays(5)));
        execution(now.minus(Duration.ofDays(90)));
        execution(now.minus(Duration.ofDays(20)));
        exported(now.minus(Duration.ofDays(120)));
        exported(now.minus(Duration.ofDays(60)));

        assertThat(cleanup(THIRTY_DAYS, Duration.ofDays(100), true, 100).purge()).isOne();

        assertThat(actions()).contains("old-not-exported", "recent").doesNotContain("old-and-exported");
        assertThat(jdbc.sql("select count(*) from query_execution").query(Integer.class).single())
                .as("execution records have their own, longer retention here")
                .isEqualTo(2);
    }

    @Test
    void anOperatorCanDropTheExportConditionAndRetentionAloneDecides() {
        event("old", now.minus(Duration.ofDays(90)));
        execution(now.minus(Duration.ofDays(90)));
        execution(now.minus(Duration.ofDays(5)));

        assertThat(cleanup(THIRTY_DAYS, THIRTY_DAYS, false, 100).purge()).isEqualTo(2);

        assertThat(actions()).containsOnly("audit.purge");
        assertThat(jdbc.sql("select count(*) from query_execution").query(Integer.class).single()).isOne();
    }

    @Test
    void deletesInBoundedBatchesAndAuditsEachBatchInItsTransaction() {
        for (int i = 0; i < 5; i++) {
            event("old-" + i, now.minus(Duration.ofDays(90)).plusSeconds(i));
        }
        exported(now.minus(Duration.ofDays(60)));

        assertThat(cleanup(THIRTY_DAYS, null, true, 2).purge()).isEqualTo(5);

        List<Integer> batches = jdbc.sql("""
                        select (attributes ->> 'rows')::int from audit_event
                        where action = 'audit.purge' and tenant_id = '' and attributes ->> 'table' = 'audit_event' order by occurred_at, id
                        """)
                .query(Integer.class)
                .list();
        assertThat(batches).containsExactlyInAnyOrder(2, 2, 1);
        assertThat(actions()).containsOnly("audit.purge");
    }

    @Test
    void aRowHeldByAnotherTransactionIsSkippedWithoutWaitingAndRemovedLater() throws Exception {
        UUID held = event("held", now.minus(Duration.ofDays(90)));
        event("free", now.minus(Duration.ofDays(90)).plusSeconds(1));
        exported(now.minus(Duration.ofDays(60)));
        AuditCleanup cleanup = cleanup(THIRTY_DAYS, null, true, 100);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("select 1 from audit_event where id = '%s' for update".formatted(held));
            }
            long started = System.nanoTime();
            assertThat(cleanup.purge()).isOne();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(actions()).contains("held").doesNotContain("free");
            holder.rollback();
        }
        assertThat(cleanup.purge()).isOne();
        assertThat(actions()).doesNotContain("held");
    }

    @Test
    void aRetentionUnderOneDayIsRefused() {
        assertThatThrownBy(() -> cleanup(Duration.ofHours(23), null, true, 100)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GA_AUDIT_RETENTION")
                .hasMessageContaining("one day");
        assertThatThrownBy(() -> cleanup(null, Duration.ofMinutes(5), true, 100)).hasMessageContaining("GA_EXECUTION_RETENTION");
    }

    private AuditCleanup cleanup(@Nullable Duration events, @Nullable Duration executions, boolean requireExport, int batchSize) {
        return new AuditCleanup(dataSource, trail, new AuditRetentionProperties(events, executions,
                new AuditRetentionProperties.Cleanup(false, Duration.ofHours(1), batchSize, requireExport)), Duration.ofSeconds(2));
    }

    private List<String> actions() {
        return jdbc.sql("select action from audit_event order by occurred_at").query(String.class).list();
    }

    private UUID event(String action, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into audit_event (id, occurred_at, tenant_id, principal_id, action, resource_type, resource_id, decision, trace_id)
                values (:id, :at, 'northstar', 'alice', :action, 'document', 'handbook', 'allow', 'trace')
                """).param("id", id).param("at", at.atOffset(ZoneOffset.UTC)).param("action", action).update();
        return id;
    }

    private void execution(Instant at) {
        jdbc.sql("""
                insert into query_execution (id, tenant_id, principal_id, trace_id, plan_hash, plan, policy_version, status, result_count, latency_ms,
                    created_at)
                values (:id, 'northstar', 'alice', 'trace', 'hash', '{}', 'abac/1', 'ok', 0, '{}', :at)
                """).param("id", UUID.randomUUID()).param("at", at.atOffset(ZoneOffset.UTC)).update();
    }

    private void exported(Instant through) {
        jdbc.sql("""
                insert into audit_export (id, through, audit_events, query_executions, manifest_sha256)
                values (:id, :through, 0, 0, 'sha')
                """).param("id", UUID.randomUUID()).param("through", through.atOffset(ZoneOffset.UTC)).update();
    }
}

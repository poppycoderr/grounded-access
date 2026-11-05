package io.groundedaccess.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.modelclient.EmbeddingClient;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

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
 * The audit tables' size and the age of their oldest row are published as gauges, so storage can be watched instead of guessed (ADR-0013).
 * The gauges read values refreshed on a schedule; exporting metrics runs no SQL.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.audit.retention.cleanup.enabled=false",
        "ga.audit.gauges.enabled=false"})
@Import({TestcontainersConfiguration.class, AuditStorageGaugesIT.Models.class})
class AuditStorageGaugesIT {

    @TestConfiguration
    static class Models {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }
    }

    @Autowired
    private AuditStorageGauges gauges;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void empty() {
        jdbc.sql("truncate audit_event, query_execution").update();
    }

    @Test
    void publishesTheEstimatedRowsAndTheOldestRowsAgePerTable() {
        Instant now = Instant.now();
        for (int i = 0; i < 40; i++) {
            event(now.minus(Duration.ofDays(10)).plusSeconds(i));
        }
        jdbc.sql("analyze audit_event").update();
        jdbc.sql("analyze query_execution").update();

        gauges.refresh();

        assertThat(gauge("ga.audit.rows", "audit_event")).isEqualTo(40);
        assertThat(gauge("ga.audit.rows", "query_execution")).isZero();
        assertThat(gauge("ga.audit.oldest_age", "audit_event")).isCloseTo(Duration.ofDays(10).toSeconds(), within(120.0));
        assertThat(gauge("ga.audit.oldest_age", "query_execution")).as("an empty table has no old row").isZero();
    }

    @Test
    void theOldestRowIsFoundThroughAnIndexNotAScan() {
        for (String table : new String[] {"audit_event", "query_execution"}) {
            String column = table.equals("audit_event") ? "occurred_at" : "created_at";
            String plan = String.join("\n", jdbc.sql("explain (costs off) select min(%s) from %s".formatted(column, table)).query(String.class).list());
            assertThat(plan).as(table).containsIgnoringCase("index").doesNotContainIgnoringCase("seq scan");
        }
    }

    private double gauge(String name, String table) {
        return meters.get(name).tag("table", table).gauge().value();
    }

    private void event(Instant at) {
        jdbc.sql("""
                insert into audit_event (id, occurred_at, tenant_id, principal_id, action, resource_type, resource_id, decision, trace_id)
                values (:id, :at, 'northstar', 'alice', 'document.read', 'document', 'handbook', 'allow', 'trace')
                """).param("id", UUID.randomUUID()).param("at", at.atOffset(ZoneOffset.UTC)).update();
    }
}

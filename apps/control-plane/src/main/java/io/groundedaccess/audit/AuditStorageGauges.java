package io.groundedaccess.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Publishes the size of the audit tables and the age of their oldest row (ADR-0013): {@code ga.audit.rows}, an estimate from the table
 * statistics, and {@code ga.audit.oldest_age} in seconds, both tagged with the table. They tell an operator when a retention or more storage is
 * needed; alerting on them is the operator's.
 *
 * <p>
 * The gauges read values that {@link #refresh()} stores, so exporting metrics never runs SQL. A failed refresh keeps the previous values.
 */
@Component
public class AuditStorageGauges {

    private static final Logger log = LoggerFactory.getLogger(AuditStorageGauges.class);

    private record Table(
            String name,

            String createdColumn,

            AtomicLong rows,

            AtomicLong oldestAgeSeconds) {
    }

    private final JdbcClient jdbc;

    private final List<Table> tables = List.of(new Table("audit_event", "occurred_at", new AtomicLong(), new AtomicLong()),
            new Table("query_execution", "created_at", new AtomicLong(), new AtomicLong()));

    public AuditStorageGauges(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        for (Table table : tables) {
            Gauge.builder("ga.audit.rows", table.rows(), AtomicLong::doubleValue)
                    .tag("table", table.name())
                    .description("Estimated rows, from the table statistics")
                    .register(meters);
            Gauge.builder("ga.audit.oldest_age", table.oldestAgeSeconds(), AtomicLong::doubleValue)
                    .tag("table", table.name())
                    .baseUnit("seconds")
                    .description("Age of the oldest row; 0 when the table is empty")
                    .register(meters);
        }
    }

    public void refresh() {
        try {
            for (Table table : tables) {
                table.rows()
                        .set(jdbc.sql("select coalesce(max(greatest(reltuples, 0))::bigint, 0) from pg_class where relname = :table and relkind = 'r'")
                                .param("table", table.name())
                                .query(Long.class)
                                .single());
                table.oldestAgeSeconds()
                        .set(jdbc.sql("select coalesce(extract(epoch from now() - min(%s))::bigint, 0) from %s".formatted(table.createdColumn(), table.name()))
                                .query(Long.class)
                                .single());
            }
        } catch (DataAccessException e) {
            log.warn("The audit storage gauges could not be refreshed: {}", e.getClass().getName());
        }
    }
}

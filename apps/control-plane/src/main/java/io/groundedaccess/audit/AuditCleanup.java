package io.groundedaccess.audit;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes audit events and execution records past their table's retention, and only those older than the latest recorded export unless the
 * operator turned that condition off (ADR-0013). With no retention configured it does nothing.
 *
 * <p>
 * Each batch is one short transaction with the audit statement timeout: it deletes at most {@code batchSize} rows, skipping rows another
 * transaction holds instead of waiting for them, and writes an {@code audit.purge} event in the same transaction, so a deletion is never
 * unaudited. The event is newer than any cut-off, so cleanup never removes the record of itself in the same run.
 */
@Component
@EnableConfigurationProperties(AuditRetentionProperties.class)
public class AuditCleanup {

    private static final Logger log = LoggerFactory.getLogger(AuditCleanup.class);

    /** Shorter than this is not an audit retention but a typo. */
    static final Duration MINIMUM_RETENTION = Duration.ofDays(1);

    private record Table(
            String name,

            String createdColumn,

            @Nullable Duration retention) {
    }

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final AuditTrail trail;

    private final AuditRetentionProperties properties;

    private final List<Table> tables;

    @Autowired
    public AuditCleanup(DataSource dataSource, AuditTrail trail, AuditRetentionProperties properties,
            @Value("${ga.audit.sql-timeout:2s}") Duration sqlTimeout) {
        check("GA_AUDIT_RETENTION", properties.events());
        check("GA_EXECUTION_RETENTION", properties.executions());
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout((int) Math.max(1, (sqlTimeout.toMillis() + 999) / 1000));
        this.jdbc = JdbcClient.create(template);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.trail = trail;
        this.properties = properties;
        this.tables = List.of(new Table("audit_event", "occurred_at", properties.events()),
                new Table("query_execution", "created_at", properties.executions()));
        if (!properties.cleanup().requireExport() && (properties.events() != null || properties.executions() != null)) {
            log.warn("Audit cleanup removes rows past their retention whether or not they were exported (GA_AUDIT_CLEANUP_REQUIRE_EXPORT=false)");
        }
    }

    private static void check(String setting, @Nullable Duration retention) {
        if (retention != null && retention.compareTo(MINIMUM_RETENTION) < 0) {
            throw new IllegalStateException("%s is %s; an audit retention must be at least one day".formatted(setting, retention));
        }
    }

    /**
     * Runs batches until nothing more can be removed and returns the number of rows removed. A batch that fails ends the run; the next run
     * starts again.
     */
    public int purge() {
        int total = 0;
        for (Table table : tables) {
            OffsetDateTime cutOff = cutOff(table);
            if (cutOff == null) {
                continue;
            }
            try {
                int removed;
                do {
                    removed = Objects.requireNonNull(transactions.execute(status -> batch(table, cutOff)));
                    total += removed;
                } while (removed > 0);
            } catch (DataAccessException | AuditUnavailableException e) {
                log.warn("Audit cleanup of {} stopped, nothing of the failed batch was removed: {}", table.name(), e.getClass().getName());
                break;
            }
        }
        if (total > 0) {
            log.info("Removed {} audit row(s) past their retention", total);
        }
        return total;
    }

    /**
     * The older of "now minus the retention" and the latest export's cut-off, or null when nothing of the table may be removed.
     */
    private @Nullable OffsetDateTime cutOff(Table table) {
        Duration retention = table.retention();
        if (retention == null) {
            return null;
        }
        OffsetDateTime byRetention = jdbc.sql("select now() - make_interval(secs => :seconds)")
                .param("seconds", retention.toSeconds())
                .query(OffsetDateTime.class)
                .single();
        if (!properties.cleanup().requireExport()) {
            return byRetention;
        }
        OffsetDateTime exported = jdbc.sql("select max(through) from audit_export").query(OffsetDateTime.class).optional().orElse(null);
        if (exported == null) {
            return null;
        }
        return exported.isBefore(byRetention) ? exported : byRetention;
    }

    private int batch(Table table, OffsetDateTime cutOff) {
        int removed = jdbc.sql("""
                        delete from %1$s where id in (
                            select id from %1$s where %2$s < :cutOff order by %2$s, id limit :batch for update skip locked)
                        """.formatted(table.name(), table.createdColumn()))
                .param("cutOff", cutOff)
                .param("batch", properties.cleanup().batchSize())
                .update();
        if (removed > 0) {
            trail.recordPurge(table.name(), cutOff, removed);
        }
        return removed;
    }
}

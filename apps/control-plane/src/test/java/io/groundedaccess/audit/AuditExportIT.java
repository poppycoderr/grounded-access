package io.groundedaccess.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.modelclient.EmbeddingClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An export copies every audit event and execution record created before a cut-off off the database, and records that it did, so that a
 * later cleanup can tell exported rows from rows that exist nowhere else (ADR-0013).
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false"})
@Import({TestcontainersConfiguration.class, AuditExportIT.Models.class})
class AuditExportIT {

    @TestConfiguration
    static class Models {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }
    }

    private static final Instant CUT_OFF = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private AuditExport export;

    @Autowired
    private JdbcClient jdbc;

    @TempDir
    private Path directory;

    @BeforeEach
    void rows() {
        jdbc.sql("truncate audit_event, query_execution, audit_export").update();
        event("old-1", CUT_OFF.minus(Duration.ofDays(30)));
        event("old-2", CUT_OFF.minusSeconds(1));
        event("new", CUT_OFF);
        execution(CUT_OFF.minus(Duration.ofDays(2)));
        execution(CUT_OFF.plusSeconds(60));
    }

    @Test
    void writesEveryRowCreatedBeforeTheCutOffAndRecordsTheExport() throws Exception {
        AuditExport.Result result = export.export(CUT_OFF, directory);

        List<String> events = Files.readAllLines(directory.resolve("audit_event.ndjson"));
        List<String> executions = Files.readAllLines(directory.resolve("query_execution.ndjson"));
        assertThat(events).hasSize(2).allSatisfy(line -> assertThat(line).startsWith("{").contains("\"action\":\"old-"));
        assertThat(events.get(0)).as("oldest first").contains("old-1");
        assertThat(executions).hasSize(1);
        assertThat(result.files()).extracting(AuditExport.ExportedFile::rows).containsExactly(2L, 1L);
        for (AuditExport.ExportedFile file : result.files()) {
            assertThat(sha256(directory.resolve(file.file()))).as(file.file()).isEqualTo(file.sha256());
        }
        String manifest = Files.readString(directory.resolve("manifest.json"));
        assertThat(manifest).contains("\"through\":\"2026-01-01T00:00:00Z\"").contains(result.files().get(0).sha256());
        assertThat(sha256(directory.resolve("manifest.json"))).isEqualTo(result.manifestSha256());

        Map<String, Object> recorded = jdbc.sql("select through, audit_events, query_executions, manifest_sha256 from audit_export").query().singleRow();
        assertThat(((java.sql.Timestamp) recorded.get("through")).toInstant()).isEqualTo(CUT_OFF);
        assertThat(recorded).containsEntry("audit_events", 2L).containsEntry("query_executions", 1L).containsEntry("manifest_sha256",
                result.manifestSha256());
        assertThat(jdbc.sql("select count(*) from audit_event").query(Integer.class).single()).as("an export deletes nothing").isEqualTo(3);
    }

    /**
     * A transaction that started before the cut-off but commits after the export's snapshot would be missing from the export, and a cleanup
     * relying on the export would remove it. The cut-off therefore has to be older than any transaction that writes audit rows.
     */
    @Test
    void refusesACutOffLessThanAnHourAgoAndWritesNothing() {
        assertThatThrownBy(() -> export.export(Instant.now().minus(Duration.ofMinutes(30)), directory)).isInstanceOf(AuditExportRefusedException.class)
                .hasMessageContaining("at least 1 hour");

        assertThat(directory).isEmptyDirectory();
        assertThat(jdbc.sql("select count(*) from audit_export").query(Integer.class).single()).isZero();
    }

    @Test
    void neverOverwritesAnEarlierExport() throws Exception {
        Files.writeString(directory.resolve("manifest.json"), "earlier");

        assertThatThrownBy(() -> export.export(CUT_OFF, directory)).isInstanceOf(AuditExportRefusedException.class).hasMessageContaining("already exists");
        assertThat(Files.readString(directory.resolve("manifest.json"))).isEqualTo("earlier");
        assertThat(jdbc.sql("select count(*) from audit_export").query(Integer.class).single()).isZero();
    }

    private void event(String action, Instant at) {
        jdbc.sql("""
                insert into audit_event (id, occurred_at, tenant_id, principal_id, action, resource_type, resource_id, decision, trace_id)
                values (:id, :at, 'northstar', 'alice', :action, 'document', 'handbook', 'allow', 'trace')
                """).param("id", UUID.randomUUID()).param("at", at.atOffset(ZoneOffset.UTC)).param("action", action).update();
    }

    private void execution(Instant at) {
        jdbc.sql("""
                insert into query_execution (id, tenant_id, principal_id, trace_id, plan_hash, plan, policy_version, status, result_count, latency_ms,
                    created_at)
                values (:id, 'northstar', 'alice', 'trace', 'hash', '{}', 'abac/1', 'ok', 0, '{}', :at)
                """).param("id", UUID.randomUUID()).param("at", at.atOffset(ZoneOffset.UTC)).update();
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}

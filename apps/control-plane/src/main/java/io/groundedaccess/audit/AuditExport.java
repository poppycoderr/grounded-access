package io.groundedaccess.audit;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies every audit event and execution record created before a cut-off to newline-delimited JSON files with a manifest, and records the
 * export in {@code audit_export} (ADR-0013). It deletes nothing.
 *
 * <p>
 * The rows are read and the export is recorded in one {@code REPEATABLE READ} transaction, so the files, their counts and the recorded row
 * describe one snapshot. A transaction that started before the cut-off and commits after that snapshot would still be missing, so the
 * cut-off must be at least {@link #MINIMUM_AGE} old, longer than any transaction that writes audit rows. Existing files are never
 * overwritten.
 */
@Component
public class AuditExport {

    /** How old the cut-off must be, measured by the database clock. */
    public static final Duration MINIMUM_AGE = Duration.ofHours(1);

    private static final List<Table> TABLES = List.of(new Table("audit_event", "occurred_at"), new Table("query_execution", "created_at"));

    private final JdbcClient jdbc;

    private final TransactionTemplate snapshot;

    public AuditExport(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * One exported table: its file name within the export directory, the number of rows and the SHA-256 of the file.
     */
    public record ExportedFile(
            String table,

            String file,

            long rows,

            String sha256) {
    }

    /**
     * What one export wrote.
     */
    public record Result(
            UUID id,

            Instant through,

            List<ExportedFile> files,

            String manifestSha256) {
    }

    private record Table(
            String name,

            String createdColumn) {
    }

    public Result export(Instant through, Path directory) throws IOException {
        Files.createDirectories(directory);
        for (String name : List.of("manifest.json", "audit_event.ndjson", "query_execution.ndjson")) {
            if (Files.exists(directory.resolve(name))) {
                throw new AuditExportRefusedException("%s already exists; an export never overwrites another".formatted(directory.resolve(name)));
            }
        }
        try {
            return Objects.requireNonNull(snapshot.execute(status -> {
                Boolean oldEnough = jdbc.sql("select :through <= now() - make_interval(secs => :age)")
                        .param("through", through.atOffset(ZoneOffset.UTC))
                        .param("age", MINIMUM_AGE.toSeconds())
                        .query(Boolean.class)
                        .single();
                if (!Boolean.TRUE.equals(oldEnough)) {
                    throw new AuditExportRefusedException("The cut-off %s must be at least 1 hour in the past".formatted(through));
                }
                List<ExportedFile> files = new ArrayList<>();
                for (Table table : TABLES) {
                    files.add(write(table, through, directory));
                }
                String manifest = manifest(through, files);
                Path manifestFile = directory.resolve("manifest.json");
                writeNew(manifestFile, manifest.getBytes(StandardCharsets.UTF_8));
                String manifestSha = HexFormat.of().formatHex(sha256().digest(manifest.getBytes(StandardCharsets.UTF_8)));
                UUID id = UUID.randomUUID();
                jdbc.sql("""
                                insert into audit_export (id, through, audit_events, query_executions, manifest_sha256)
                                values (:id, :through, :events, :executions, :sha)
                                """)
                        .param("id", id)
                        .param("through", through.atOffset(ZoneOffset.UTC))
                        .param("events", files.get(0).rows())
                        .param("executions", files.get(1).rows())
                        .param("sha", manifestSha)
                        .update();
                return new Result(id, through, List.copyOf(files), manifestSha);
            }));
        } catch (RuntimeException e) {
            // Nothing was recorded, so files of this attempt are not an export; they are removed so the export can be run again
            for (String name : List.of("manifest.json", "audit_event.ndjson", "query_execution.ndjson")) {
                Files.deleteIfExists(directory.resolve(name));
            }
            if (e instanceof UncheckedIOException io) {
                throw io.getCause();
            }
            throw e;
        }
    }

    /**
     * Streams the table's rows, oldest first, one JSON object per line as PostgreSQL renders the row, hashing the bytes as they are written.
     */
    private ExportedFile write(Table table, Instant through, Path directory) {
        String file = table.name() + ".ndjson";
        MessageDigest digest = sha256();
        long[] rows = {0};
        try (OutputStream out = Files.newOutputStream(directory.resolve(file), StandardOpenOption.CREATE_NEW);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(out, digest), StandardCharsets.UTF_8))) {
            jdbc.sql("select row_to_json(t)::text from (select * from %s where %s < :through order by %s, id) t".formatted(table.name(),
                    table.createdColumn(), table.createdColumn()))
                    .param("through", through.atOffset(ZoneOffset.UTC))
                    .query(rs -> {
                        try {
                            writer.write(rs.getString(1));
                            writer.write('\n');
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        rows[0]++;
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ExportedFile(table.name(), file, rows[0], HexFormat.of().formatHex(digest.digest()));
    }

    private static String manifest(Instant through, List<ExportedFile> files) {
        StringBuilder json = new StringBuilder("{\"manifestVersion\":1,\"through\":\"").append(through).append("\",\"files\":[");
        for (int i = 0; i < files.size(); i++) {
            ExportedFile file = files.get(i);
            json.append(i == 0 ? "" : ",")
                    .append("{\"table\":\"%s\",\"file\":\"%s\",\"rows\":%d,\"sha256\":\"%s\"}".formatted(file.table(), file.file(), file.rows(), file.sha256()));
        }
        return json.append("]}\n").toString();
    }

    private static void writeNew(Path file, byte[] content) {
        try {
            Files.write(file, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

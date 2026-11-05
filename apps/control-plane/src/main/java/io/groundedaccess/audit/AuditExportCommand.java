package io.groundedaccess.audit;

import java.nio.file.Path;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs one audit export and stops the process: the control plane started with the {@code audit-export} profile, as
 * {@code scripts/export-audit} does. It serves no requests and runs no scheduled work.
 */
@Component
@Profile("audit-export")
class AuditExportCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditExportCommand.class);

    private final AuditExport export;

    private final ConfigurableApplicationContext context;

    private final Instant through;

    private final Path directory;

    AuditExportCommand(AuditExport export, ConfigurableApplicationContext context, @Value("${ga.audit.export.through}") Instant through,
            @Value("${ga.audit.export.directory}") Path directory) {
        this.export = export;
        this.context = context;
        this.through = through;
        this.directory = directory;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int status = 0;
        try {
            AuditExport.Result result = export.export(through, directory);
            log.info("Audit export {} through {}: {} audit events, {} execution records, manifest sha256 {}", result.id(), result.through(),
                    result.files().get(0).rows(), result.files().get(1).rows(), result.manifestSha256());
        } catch (AuditExportRefusedException e) {
            log.error("Audit export refused, nothing was recorded: {}", e.getMessage());
            status = 1;
        } catch (Exception e) {
            log.error("Audit export failed, nothing was recorded: {}", e.getClass().getName());
            status = 1;
        }
        int exit = status;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}

package io.groundedaccess.audit;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long audit events and execution records are kept (ADR-0013). Each retention is unset by default, and then nothing of that table is
 * deleted. {@code cleanup.requireExport} keeps every row that no recorded export covers; turning it off lets retention alone decide.
 */
@ConfigurationProperties("ga.audit.retention")
public record AuditRetentionProperties(
        @Nullable Duration events,

        @Nullable Duration executions,

        Cleanup cleanup) {

    /**
     * When cleanup runs, how many rows one transaction may delete, and whether a recorded export is required.
     */
    public record Cleanup(
            boolean enabled,

            Duration interval,

            int batchSize,

            boolean requireExport) {
    }
}

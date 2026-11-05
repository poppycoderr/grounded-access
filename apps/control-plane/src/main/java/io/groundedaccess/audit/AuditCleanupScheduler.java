package io.groundedaccess.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs audit cleanup periodically. It removes nothing unless a retention is configured. Turned off with
 * {@code ga.audit.retention.cleanup.enabled=false}, which tests use to run cleanup by hand.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "ga.audit.retention.cleanup.enabled", matchIfMissing = true)
class AuditCleanupScheduler {

    private final AuditCleanup cleanup;

    AuditCleanupScheduler(AuditCleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${ga.audit.retention.cleanup.interval}")
    void run() {
        cleanup.purge();
    }
}

package io.groundedaccess.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Refreshes the audit storage gauges periodically. Turned off with {@code ga.audit.gauges.enabled=false}, which tests use to refresh by hand.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "ga.audit.gauges.enabled", matchIfMissing = true)
class AuditStorageGaugesScheduler {

    private final AuditStorageGauges gauges;

    AuditStorageGaugesScheduler(AuditStorageGauges gauges) {
        this.gauges = gauges;
    }

    @Scheduled(fixedDelayString = "${ga.audit.gauges.interval}")
    void run() {
        gauges.refresh();
    }
}

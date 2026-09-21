package io.groundedaccess.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls for runnable jobs. Turned off with {@code ga.ingestion.worker.enabled=false}, which tests use to run the worker by hand.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "ga.ingestion.worker.enabled", matchIfMissing = true)
class IngestionWorkerScheduler {

    private final IngestionWorker worker;

    IngestionWorkerScheduler(IngestionWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${ga.ingestion.worker.poll-interval}")
    void poll() {
        worker.drain();
    }
}

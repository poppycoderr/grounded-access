package io.groundedaccess.corpus;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs cleanup periodically. Turned off with {@code ga.corpus.cleanup.enabled=false}, which tests use to run cleanup by hand.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "ga.corpus.cleanup.enabled", matchIfMissing = true)
class CorpusCleanupScheduler {

    private final CorpusCleanup cleanup;

    CorpusCleanupScheduler(CorpusCleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${ga.corpus.cleanup.interval}")
    void run() {
        cleanup.purge();
    }
}

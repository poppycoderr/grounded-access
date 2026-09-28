package io.groundedaccess.corpus;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often cleanup runs and how many documents one run may lock.
 */
@ConfigurationProperties("ga.corpus.cleanup")
public record CorpusCleanupProperties(
        boolean enabled,

        Duration interval,

        int batchSize) {
}

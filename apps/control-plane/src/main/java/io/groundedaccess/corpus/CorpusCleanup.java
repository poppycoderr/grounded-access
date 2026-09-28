package io.groundedaccess.corpus;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes versions no query can reach: versions replaced by a newer one, and every version of a deleted document. Their chunks go with them
 * through the foreign key cascade. Retrieval never depends on this running, because queries already join only the active version of an active
 * document; cleanup only reclaims storage.
 */
@Component
@EnableConfigurationProperties(CorpusCleanupProperties.class)
public class CorpusCleanup {

    private static final Logger log = LoggerFactory.getLogger(CorpusCleanup.class);

    private final CorpusWriter writer;

    private final TransactionTemplate transactions;

    private final CorpusCleanupProperties properties;

    public CorpusCleanup(CorpusWriter writer, TransactionTemplate transactions, CorpusCleanupProperties properties) {
        this.writer = writer;
        this.transactions = transactions;
        this.properties = properties;
    }

    /**
     * Runs batches until nothing is left to remove and returns the number of versions removed. Documents locked by an ingestion are skipped and
     * picked up by a later run.
     */
    public int purge() {
        int total = 0;
        int removed;
        do {
            removed = Objects.requireNonNull(transactions.execute(status -> writer.purgeUnreachableVersions(properties.batchSize())));
            total += removed;
        } while (removed > 0);
        if (total > 0) {
            log.info("Removed {} unreachable document version(s)", total);
        }
        return total;
    }
}

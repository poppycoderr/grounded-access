package io.groundedaccess.modelclient;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Python model service. {@code embeddingModel} and {@code rerankerModel} pin the models so a silently swapped
 * model fails fast. {@code rerankTimeout} bounds how long a query waits for a free reranking slot, and again how long the call itself may
 * take. {@code rerankMaxConcurrent} is the number of slots: a cross-encoder on a CPU does not get faster when calls overlap.
 */
@ConfigurationProperties("ga.model-service")
public record ModelServiceProperties(
        URI baseUrl,

        String embeddingModel,

        int batchSize,

        Duration connectTimeout,

        Duration readTimeout,

        String rerankerModel,

        Duration rerankTimeout,

        int rerankMaxConcurrent) {
}

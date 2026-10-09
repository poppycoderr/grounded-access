package io.groundedaccess.modelclient;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Python model service. {@code embeddingModel} and {@code rerankerModel} pin the models so a silently swapped
 * model fails fast. {@code rerankTimeout} bounds what reranking may add to a query.
 */
@ConfigurationProperties("ga.model-service")
public record ModelServiceProperties(
        URI baseUrl,

        String embeddingModel,

        int batchSize,

        Duration connectTimeout,

        Duration readTimeout,

        String rerankerModel,

        Duration rerankTimeout) {
}

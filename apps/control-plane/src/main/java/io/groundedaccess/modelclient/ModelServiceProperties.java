package io.groundedaccess.modelclient;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Python model service. {@code embeddingModel} pins the model so a silently swapped model fails fast.
 */
@ConfigurationProperties("ga.model-service")
public record ModelServiceProperties(
        URI baseUrl,

        String embeddingModel,

        int batchSize,

        Duration connectTimeout,

        Duration readTimeout) {
}

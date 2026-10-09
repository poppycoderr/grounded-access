package io.groundedaccess.modelclient;

import java.net.URI;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection to an OpenAI-compatible chat endpoint, for example a local Ollama server. Generation is disabled while {@code baseUrl} is unset.
 */
@ConfigurationProperties("ga.chat")
public record ChatProperties(
        @Nullable URI baseUrl,

        @Nullable String model,

        @Nullable String apiKey,

        Duration connectTimeout,

        Duration readTimeout) {
}

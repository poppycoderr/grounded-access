package io.groundedaccess.modelclient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link EmbeddingClient} over the model-service HTTP contract (packages/contracts/model-service.openapi.json). Splits large inputs into batches the
 * service accepts.
 */
@Component
@EnableConfigurationProperties(ModelServiceProperties.class)
public class ModelServiceEmbeddingClient implements EmbeddingClient {

    private final RestClient http;

    private final ModelServiceProperties properties;

    public ModelServiceEmbeddingClient(RestClient.Builder builder, ModelServiceProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(properties.connectTimeout(), properties.readTimeout());
        // HTTP/1.1 on purpose: the JDK client otherwise sends an h2c upgrade request, and uvicorn drops the body of upgrade requests
        var requestFactory = ClientHttpRequestFactoryBuilder.jdk().withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1)).build(settings);
        this.http = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.properties = properties;
    }

    @Override
    public String modelName() {
        return properties.embeddingModel();
    }

    @Override
    public Embeddings embed(List<String> texts, InputType inputType) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        String model = properties.embeddingModel();
        String revision = "";
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(from + properties.batchSize(), texts.size()));
            EmbedResponse response = Objects.requireNonNull(http.post()
                    .uri("/v1/embed")
                    .body(new EmbedRequest(model, inputType.name().toLowerCase(Locale.ROOT), batch))
                    .retrieve()
                    .body(EmbedResponse.class));
            vectors.addAll(response.vectors());
            revision = response.revision();
        }
        return new Embeddings(model, revision, vectors);
    }

    record EmbedRequest(
            String model,

            String input_type,

            List<String> texts) {
    }

    record EmbedResponse(
            String model,

            String revision,

            int dimensions,

            List<float[]> vectors) {
    }
}

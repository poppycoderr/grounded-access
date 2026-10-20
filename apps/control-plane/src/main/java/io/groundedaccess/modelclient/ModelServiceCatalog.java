package io.groundedaccess.modelclient;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link ModelCatalog} over the model service's {@code GET /v1/models}.
 */
@Component
public class ModelServiceCatalog implements ModelCatalog {

    private final RestClient http;

    private final ModelServiceProperties properties;

    public ModelServiceCatalog(RestClient.Builder builder, ModelServiceProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(properties.connectTimeout(), properties.connectTimeout());
        // HTTP/1.1 on purpose, for the same reason as the embedding client: uvicorn drops the body of h2c upgrade requests
        var requestFactory = ClientHttpRequestFactoryBuilder.jdk().withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1)).build(settings);
        this.http = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.properties = properties;
    }

    @Override
    public Optional<String> rerankerRevision() {
        try {
            ModelsResponse response = http.get().uri("/v1/models").retrieve().body(ModelsResponse.class);
            if (response == null || response.models() == null) {
                return Optional.empty();
            }
            return response.models()
                    .stream()
                    .filter(model -> "rerank".equals(model.task()) && properties.rerankerModel().equals(model.name()))
                    .map(ModelInfo::revision)
                    .filter(revision -> revision != null && !revision.isBlank())
                    .findFirst();
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    record ModelsResponse(
            @Nullable List<ModelInfo> models) {
    }

    record ModelInfo(
            @Nullable String name,

            @Nullable String task,

            @Nullable String revision) {
    }
}

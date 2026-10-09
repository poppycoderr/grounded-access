package io.groundedaccess.modelclient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link RerankClient} over the model-service HTTP contract. Reranking sits on the query path, so it has its own, much shorter read timeout
 * than embedding: a slow reranker should cost a bounded delay and then give way to the fused order.
 */
@Component
public class ModelServiceRerankClient implements RerankClient {

    private final RestClient http;

    private final ModelServiceProperties properties;

    public ModelServiceRerankClient(RestClient.Builder builder, ModelServiceProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(properties.connectTimeout(), properties.rerankTimeout());
        // HTTP/1.1 on purpose, for the same reason as the embedding client: uvicorn drops the body of h2c upgrade requests
        var requestFactory = ClientHttpRequestFactoryBuilder.jdk().withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1)).build(settings);
        this.http = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.properties = properties;
    }

    @Override
    public String modelName() {
        return properties.rerankerModel();
    }

    @Override
    public RerankScores score(String query, List<String> passages) {
        List<Passage> numbered = new ArrayList<>(passages.size());
        for (int i = 0; i < passages.size(); i++) {
            numbered.add(new Passage(Integer.toString(i), passages.get(i)));
        }
        RerankResponse response = Objects.requireNonNull(http.post()
                .uri("/v1/rerank")
                .body(new RerankRequest(properties.rerankerModel(), query, numbered))
                .retrieve()
                .body(RerankResponse.class));
        if (response.scores().size() != passages.size()) {
            throw new RestClientException("the model service returned " + response.scores().size() + " scores for " + passages.size() + " passages");
        }
        return new RerankScores(response.model(), response.revision(), response.scores().stream().map(Score::score).toList());
    }

    record Passage(
            String id,

            String text) {
    }

    record RerankRequest(
            String model,

            String query,

            List<Passage> passages) {
    }

    record Score(
            String id,

            double score) {
    }

    record RerankResponse(
            String model,

            String revision,

            List<Score> scores) {
    }
}

package io.groundedaccess.modelclient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link RerankClient} over the model-service HTTP contract. Reranking sits on the query path, so it has its own, much shorter read timeout
 * than embedding: a slow reranker should cost a bounded delay and then give way to the fused order.
 *
 * <p>
 * Only a limited number of calls are in flight at once, and a query that cannot get a slot within the timeout is not sent at all. A
 * timeout does not cancel the work in the model service, so without the limit an overloaded reranker keeps computing answers nobody waits
 * for, and the embedding calls of every other query queue behind them.
 */
@Component
public class ModelServiceRerankClient implements RerankClient {

    private final RestClient http;

    private final ModelServiceProperties properties;

    private final Semaphore slots;

    public ModelServiceRerankClient(RestClient.Builder builder, ModelServiceProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(properties.connectTimeout(), properties.rerankTimeout());
        // HTTP/1.1 on purpose, for the same reason as the embedding client: uvicorn drops the body of h2c upgrade requests
        var requestFactory = ClientHttpRequestFactoryBuilder.jdk().withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1)).build(settings);
        this.http = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.properties = properties;
        this.slots = new Semaphore(properties.rerankMaxConcurrent(), true);
    }

    @Override
    public String modelName() {
        return properties.rerankerModel();
    }

    @Override
    public RerankScores score(String query, List<String> passages) {
        try {
            if (!slots.tryAcquire(properties.rerankTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new RerankBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RerankBusyException();
        }
        try {
            return send(query, passages);
        } finally {
            slots.release();
        }
    }

    private RerankScores send(String query, List<String> passages) {
        List<Passage> numbered = new ArrayList<>(passages.size());
        for (int i = 0; i < passages.size(); i++) {
            numbered.add(new Passage(Integer.toString(i), passages.get(i)));
        }
        RerankResponse response = Objects.requireNonNull(http.post()
                .uri("/v1/rerank")
                .body(new RerankRequest(properties.rerankerModel(), query, numbered))
                .retrieve()
                .body(RerankResponse.class));
        return check(response, numbered);
    }

    /**
     * One finite score per passage, for the ids that were sent, in request order, from the configured model at a known revision.
     */
    private RerankScores check(RerankResponse response, List<Passage> sent) {
        String revision = response.revision();
        List<@Nullable Score> scores = response.scores();
        if (!properties.rerankerModel().equals(response.model())) {
            throw new InvalidModelResponseException("expected model %s, got %s".formatted(properties.rerankerModel(), response.model()));
        }
        if (revision == null || revision.isBlank() || "unknown".equals(revision)) {
            throw new InvalidModelResponseException("the model service did not report the revision of %s".formatted(response.model()));
        }
        if (scores == null || scores.size() != sent.size()) {
            throw new InvalidModelResponseException("expected %d scores, got %s".formatted(sent.size(), scores == null ? "none" : scores.size()));
        }
        List<Double> values = new ArrayList<>(sent.size());
        for (int i = 0; i < sent.size(); i++) {
            Score score = scores.get(i);
            if (score == null || !sent.get(i).id().equals(score.id())) {
                throw new InvalidModelResponseException("score %d is not for passage %d".formatted(i, i));
            }
            if (!Double.isFinite(score.score())) {
                throw new InvalidModelResponseException("score %d is not finite".formatted(i));
            }
            values.add(score.score());
        }
        return new RerankScores(properties.rerankerModel(), revision, values);
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
            @Nullable String id,

            double score) {
    }

    record RerankResponse(
            @Nullable String model,

            @Nullable String revision,

            @Nullable List<@Nullable Score> scores) {
    }
}

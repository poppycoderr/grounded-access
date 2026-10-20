package io.groundedaccess.modelclient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link EmbeddingClient} over the model-service HTTP contract (packages/contracts/model-service.openapi.json). Splits large inputs into batches the
 * service accepts, and checks every batch before using it: the configured model at a known revision, one vector per text, each of the stated
 * size, finite and not all zeros, and the same revision, prefix and size in every batch, so one call never mixes two models.
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
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("nothing to embed");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        String model = properties.embeddingModel();
        @Nullable Embeddings first = null;
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(from + properties.batchSize(), texts.size()));
            EmbedResponse response = Objects.requireNonNull(http.post()
                    .uri("/v1/embed")
                    .body(new EmbedRequest(model, inputType.name().toLowerCase(Locale.ROOT), batch))
                    .retrieve()
                    .onStatus(status -> status.value() == 422, (request, refused) -> {
                        String body = new String(refused.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (body.contains("\"TEXT_TOO_LONG\"")) {
                            throw new TextTooLongException("the model service refused a text as too long: " + message(body));
                        }
                        throw new HttpClientErrorException(refused.getStatusCode(), body);
                    })
                    .body(EmbedResponse.class));
            Embeddings checked = check(response, batch.size());
            if (first == null) {
                first = checked;
            } else if (!first.revision().equals(checked.revision()) || !first.prefix().equals(checked.prefix())
                    || first.dimensions() != checked.dimensions()) {
                throw new InvalidModelResponseException("the batches of one call came from %s and %s".formatted(first.modelId(), checked.modelId()));
            }
            vectors.addAll(checked.vectors());
        }
        Embeddings checked = Objects.requireNonNull(first);
        return new Embeddings(checked.model(), checked.revision(), checked.prefix(), checked.dimensions(), vectors);
    }

    @Override
    public List<Integer> fittingChars(List<String> texts, int budget) {
        List<Integer> fitting = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(from + properties.batchSize(), texts.size()));
            TokenizeResponse response = Objects.requireNonNull(
                    http.post().uri("/v1/tokenize").body(new TokenizeRequest(batch, budget)).retrieve().body(TokenizeResponse.class));
            List<@Nullable Integer> counted = response.fittingChars();
            if (counted == null || counted.size() != batch.size()) {
                throw new InvalidModelResponseException("expected %d fitting lengths, got %s".formatted(batch.size(), counted == null ? "none" : counted.size()));
            }
            for (int i = 0; i < batch.size(); i++) {
                Integer value = counted.get(i);
                if (value == null || value < 0 || value > batch.get(i).length()) {
                    throw new InvalidModelResponseException("fitting length %d is outside its text".formatted(i));
                }
                fitting.add(value);
            }
        }
        return fitting;
    }

    /**
     * The message of a model-service error body; it names counts and limits only.
     */
    private static String message(String body) {
        int start = body.indexOf("\"message\":\"");
        return start < 0 ? "no message" : body.substring(start + 11, Math.max(start + 11, body.indexOf('"', start + 11)));
    }

    private Embeddings check(EmbedResponse response, int texts) {
        String model = response.model();
        String revision = response.revision();
        String prefix = response.prefix();
        List<float @Nullable []> vectors = response.vectors();
        if (!properties.embeddingModel().equals(model)) {
            throw new InvalidModelResponseException("expected model %s, got %s".formatted(properties.embeddingModel(), model));
        }
        if (revision == null || revision.isBlank() || "unknown".equals(revision)) {
            throw new InvalidModelResponseException("the model service did not report the revision of %s".formatted(model));
        }
        if (prefix == null) {
            throw new InvalidModelResponseException("the model service did not report the prefix it applied");
        }
        if (response.dimensions() <= 0) {
            throw new InvalidModelResponseException("the model service reported %d dimensions".formatted(response.dimensions()));
        }
        if (vectors == null || vectors.size() != texts) {
            throw new InvalidModelResponseException("expected %d vectors, got %s".formatted(texts, vectors == null ? "none" : vectors.size()));
        }
        List<float[]> checked = new ArrayList<>(texts);
        for (int i = 0; i < texts; i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != response.dimensions()) {
                throw new InvalidModelResponseException("vector %d does not have the reported %d dimensions".formatted(i, response.dimensions()));
            }
            boolean nonZero = false;
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new InvalidModelResponseException("vector %d contains a value that is not finite".formatted(i));
                }
                nonZero |= value != 0;
            }
            if (!nonZero) {
                throw new InvalidModelResponseException("vector %d is all zeros".formatted(i));
            }
            checked.add(vector);
        }
        return new Embeddings(properties.embeddingModel(), revision, prefix, response.dimensions(), checked);
    }

    record EmbedRequest(
            String model,

            String input_type,

            List<String> texts) {
    }

    record TokenizeRequest(
            List<String> texts,

            int budget) {
    }

    record TokenizeResponse(
            int max_tokens,

            @Nullable List<@Nullable Integer> fitting_chars) {

        @Nullable List<@Nullable Integer> fittingChars() {
            return fitting_chars;
        }
    }

    record EmbedResponse(
            @Nullable String model,

            @Nullable String revision,

            @Nullable String prefix,

            int dimensions,

            @Nullable List<float @Nullable []> vectors) {
    }
}

package io.groundedaccess.modelclient;

import java.net.http.HttpClient;
import java.time.Duration;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link EmbeddingClient} over the model-service HTTP contract (packages/contracts/model-service.openapi.json). Splits large inputs into batches the
 * service accepts, and checks every batch before using it: the configured model at a known revision, one vector per text, each of the stated
 * size, finite and not all zeros, and the same revision, prefix and size in every batch, so one call never mixes two models.
 */
@Component
@EnableConfigurationProperties(ModelServiceProperties.class)
public class ModelServiceEmbeddingClient implements EmbeddingClient {

    static final String DEADLINE_HEADER = "X-Deadline-Ms";

    private final RestClient http;

    /** The same service with the shorter timeout of an interactive query embedding. */
    private final RestClient queries;

    private final ModelServiceProperties properties;

    public ModelServiceEmbeddingClient(RestClient.Builder builder, ModelServiceProperties properties) {
        this.http = client(builder.clone(), properties, properties.readTimeout());
        this.queries = client(builder.clone(), properties, properties.queryTimeout());
        this.properties = properties;
    }

    private static RestClient client(RestClient.Builder builder, ModelServiceProperties properties, Duration timeout) {
        HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(properties.connectTimeout(), timeout);
        // HTTP/1.1 on purpose: the JDK client otherwise sends an h2c upgrade request, and uvicorn drops the body of upgrade requests
        var requestFactory = ClientHttpRequestFactoryBuilder.jdk().withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1)).build(settings);
        return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public Duration timeout(InputType inputType) {
        return inputType == InputType.QUERY ? properties.queryTimeout() : properties.readTimeout();
    }

    @Override
    public String modelName() {
        return properties.embeddingModel();
    }

    @Override
    public Embeddings embed(List<String> texts, InputType inputType) {
        return embed(texts, inputType, timeout(inputType));
    }

    @Override
    public Embeddings embed(List<String> texts, InputType inputType, Duration remaining) {
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("nothing to embed");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        String model = properties.embeddingModel();
        @Nullable Embeddings first = null;
        long queueMs = 0;
        long computeMs = 0;
        long deadline = System.nanoTime() + Math.min(remaining.toNanos(), timeout(inputType).toNanos());
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(from + properties.batchSize(), texts.size()));
            ResponseEntity<EmbedResponse> entity = (inputType == InputType.QUERY ? queries : http).post()
                    .uri("/v1/embed")
                    .header(DEADLINE_HEADER, Long.toString(Math.max(0, (deadline - System.nanoTime()) / 1_000_000)))
                    .body(new EmbedRequest(model, inputType.name().toLowerCase(Locale.ROOT), batch))
                    .retrieve()
                    .onStatus(status -> status.value() == 422 || status.value() == 503, (request, refused) -> {
                        String body = new String(refused.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (body.contains("\"TEXT_TOO_LONG\"")) {
                            throw new TextTooLongException("the model service refused a text as too long: " + message(body));
                        }
                        refusal(body);
                        if (refused.getStatusCode().is4xxClientError()) {
                            throw new HttpClientErrorException(refused.getStatusCode(), body);
                        }
                        throw new HttpServerErrorException(refused.getStatusCode(), body);
                    })
                    .toEntity(EmbedResponse.class);
            queueMs += timing(entity.getHeaders(), "X-Queue-Ms");
            computeMs += timing(entity.getHeaders(), "X-Compute-Ms");
            Embeddings checked = check(Objects.requireNonNull(entity.getBody()), batch.size());
            if (first == null) {
                first = checked;
            } else if (!first.revision().equals(checked.revision()) || !first.prefix().equals(checked.prefix())
                    || first.dimensions() != checked.dimensions()) {
                throw new InvalidModelResponseException("the batches of one call came from %s and %s".formatted(first.modelId(), checked.modelId()));
            }
            vectors.addAll(checked.vectors());
        }
        Embeddings checked = Objects.requireNonNull(first);
        return new Embeddings(checked.model(), checked.revision(), checked.prefix(), checked.dimensions(), vectors, queueMs, computeMs);
    }

    /**
     * A 503 that says the work was not started in time; anything else falls through to the caller's handling.
     */
    static void refusal(String body) {
        for (String reason : List.of("BUSY", "DEADLINE_EXCEEDED")) {
            if (body.contains("\"" + reason + "\"")) {
                throw new ModelBusyException(reason);
            }
        }
    }

    static long timing(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public List<Integer> fittingChars(List<String> texts, int budget) {
        List<Integer> fitting = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(from + properties.batchSize(), texts.size()));
            TokenizeResponse response = Objects.requireNonNull(http.post()
                    .uri("/v1/tokenize")
                    .header(DEADLINE_HEADER, Long.toString(properties.readTimeout().toMillis()))
                    .body(new TokenizeRequest(batch, budget))
                    .retrieve()
                    .onStatus(status -> status.value() == 503, (request, refused) -> {
                        String body = new String(refused.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        refusal(body);
                        throw new HttpServerErrorException(refused.getStatusCode(), body);
                    })
                    .body(TokenizeResponse.class));
            List<@Nullable Integer> counted = response.fittingChars();
            if (counted == null || counted.size() != batch.size()) {
                throw new InvalidModelResponseException("expected %d fitting lengths, got %s".formatted(batch.size(), counted == null ? "none" : counted.size()));
            }
            for (int i = 0; i < batch.size(); i++) {
                Integer value = counted.get(i);
                if (value == null || value < 0 || value > batch.get(i).codePointCount(0, batch.get(i).length())) {
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

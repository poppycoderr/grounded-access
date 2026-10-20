package io.groundedaccess.modelclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Exercises the client against a real HTTP server, checking the wire format of packages/contracts/model-service.openapi.json.
 */
class ModelServiceEmbeddingClientTest {

    private final List<String> requests = new CopyOnWriteArrayList<>();

    /** The response to each request, given the number of texts in it and the request's position, starting at 0. */
    private volatile BiFunction<Integer, Integer, String> answer = (texts, call) -> response("m", "r1", "\"\"", 2, String.join(",", Collections.nCopies(texts, "[0.6,0.8]")));

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embed", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestHeaders().getFirst("Upgrade") + "|" + body);
            int texts = body.substring(body.indexOf("\"texts\":[")).split("\",\"", -1).length;
            byte[] response = answer.apply(texts, requests.size() - 1).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String response(String model, String revision, String prefix, int dimensions, String vectors) {
        return """
                {"model":"%s","revision":"%s","prefix":%s,"dimensions":%d,"vectors":[%s]}
                """.formatted(model, revision, prefix, dimensions, vectors);
    }

    private ModelServiceEmbeddingClient client() {
        var properties = new ModelServiceProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "m", 2, Duration.ofSeconds(1),
                Duration.ofSeconds(2), "reranker", Duration.ofSeconds(1), 1);
        return new ModelServiceEmbeddingClient(RestClient.builder(), properties);
    }

    @Test
    void sendsTheContractRequestOverPlainHttp11AndBatchesLargeInputs() {
        Embeddings embeddings = client().embed(List.of("a", "b", "c"), InputType.PASSAGE);

        assertThat(embeddings.vectors()).hasSize(3).allSatisfy(v -> assertThat(v).containsExactly(0.6f, 0.8f));
        assertThat(embeddings.modelId()).isEqualTo("m@r1");
        assertThat(embeddings.prefix()).isEmpty();
        assertThat(embeddings.dimensions()).isEqualTo(2);
        assertThat(requests).containsExactly(
                "null|{\"model\":\"m\",\"input_type\":\"passage\",\"texts\":[\"a\",\"b\"]}",
                "null|{\"model\":\"m\",\"input_type\":\"passage\",\"texts\":[\"c\"]}");
    }

    @Test
    void refusesAResponseThatBreaksTheContract() {
        List<String[]> broken = new ArrayList<>();
        broken.add(new String[] {"another model", response("other", "r1", "\"\"", 2, "[0.6,0.8]")});
        broken.add(new String[] {"did not report the revision", response("m", "unknown", "\"\"", 2, "[0.6,0.8]")});
        broken.add(new String[] {"did not report the revision", response("m", "", "\"\"", 2, "[0.6,0.8]")});
        broken.add(new String[] {"did not report the prefix", response("m", "r1", "null", 2, "[0.6,0.8]")});
        broken.add(new String[] {"expected 1 vectors, got 2", response("m", "r1", "\"\"", 2, "[0.6,0.8],[0.6,0.8]")});
        broken.add(new String[] {"does not have the reported 2 dimensions", response("m", "r1", "\"\"", 2, "[0.6,0.8,0.0]")});
        broken.add(new String[] {"reported 0 dimensions", response("m", "r1", "\"\"", 0, "[]")});
        broken.add(new String[] {"not finite", response("m", "r1", "\"\"", 2, "[1e39,0.8]")});
        broken.add(new String[] {"all zeros", response("m", "r1", "\"\"", 2, "[0.0,0.0]")});
        for (String[] each : broken) {
            String defect = each[0];
            answer = (texts, call) -> each[1];
            assertThatThrownBy(() -> client().embed(List.of("a"), InputType.QUERY)).as(defect)
                    .isInstanceOf(InvalidModelResponseException.class)
                    .hasMessageContaining(defect.equals("another model") ? "expected model m, got other" : defect);
        }
    }

    @Test
    void refusesACallWhoseBatchesCameFromDifferentRevisionsPrefixesOrSizes() {
        List<BiFunction<Integer, Integer, String>> mixed = List.of(
                (texts, call) -> response("m", call == 0 ? "r1" : "r2", "\"\"", 2, String.join(",", Collections.nCopies(texts, "[0.6,0.8]"))),
                (texts, call) -> response("m", "r1", call == 0 ? "\"\"" : "\"q: \"", 2, String.join(",", Collections.nCopies(texts, "[0.6,0.8]"))),
                (texts, call) -> response("m", "r1", "\"\"", call == 0 ? 2 : 3,
                        String.join(",", Collections.nCopies(texts, call == 0 ? "[0.6,0.8]" : "[0.6,0.8,0.0]"))));
        for (BiFunction<Integer, Integer, String> responses : mixed) {
            requests.clear();
            answer = responses;
            assertThatThrownBy(() -> client().embed(List.of("a", "b", "c"), InputType.PASSAGE)).isInstanceOf(InvalidModelResponseException.class)
                    .hasMessageContaining("the batches of one call came from");
        }
    }
}

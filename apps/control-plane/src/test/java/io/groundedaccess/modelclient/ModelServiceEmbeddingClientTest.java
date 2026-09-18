package io.groundedaccess.modelclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Exercises the client against a real HTTP server, checking the wire format of packages/contracts/model-service.openapi.json.
 */
class ModelServiceEmbeddingClientTest {

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embed", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestHeaders().getFirst("Upgrade") + "|" + body);
            int texts = body.substring(body.indexOf("\"texts\":[")).split("\",\"", -1).length;
            String vectors = String.join(",", Collections.nCopies(texts, "[0.6,0.8]"));
            byte[] response = """
                    {"model":"m","revision":"r1","dimensions":2,"vectors":[%s]}
                    """.formatted(vectors).getBytes(StandardCharsets.UTF_8);
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

    @Test
    void sendsTheContractRequestOverPlainHttp11AndBatchesLargeInputs() {
        var properties = new ModelServiceProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "m", 2, Duration.ofSeconds(1),
                Duration.ofSeconds(2));
        var client = new ModelServiceEmbeddingClient(RestClient.builder(), properties);

        Embeddings embeddings = client.embed(List.of("a", "b", "c"), InputType.PASSAGE);

        assertThat(embeddings.vectors()).hasSize(3).allSatisfy(v -> assertThat(v).containsExactly(0.6f, 0.8f));
        assertThat(embeddings.modelId()).isEqualTo("m@r1");
        assertThat(requests).containsExactly(
                "null|{\"model\":\"m\",\"input_type\":\"passage\",\"texts\":[\"a\",\"b\"]}",
                "null|{\"model\":\"m\",\"input_type\":\"passage\",\"texts\":[\"c\"]}");
    }
}

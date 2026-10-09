package io.groundedaccess.modelclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Exercises the client against a real HTTP server, checking the wire format and the short timeout that keeps reranking off the critical path.
 */
class ModelServiceRerankClientTest {

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> response = new AtomicReference<>("");

    private volatile long delayMillis;

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> {
            requests.add(exchange.getRequestHeaders().getFirst("Upgrade") + "|" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsNumberedPassagesAndReturnsTheScoresInRequestOrder() {
        response.set("{\"model\":\"m\",\"revision\":\"r7\",\"scores\":[{\"id\":\"0\",\"score\":-2.5},{\"id\":\"1\",\"score\":4.0}]}");

        RerankScores scores = client(Duration.ofSeconds(2)).score("paid volunteer days", List.of("travel policy", "volunteer policy"));

        assertThat(scores.scores()).containsExactly(-2.5, 4.0);
        assertThat(scores.modelId()).isEqualTo("m@r7");
        assertThat(requests).containsExactly("null|{\"model\":\"m\",\"query\":\"paid volunteer days\","
                + "\"passages\":[{\"id\":\"0\",\"text\":\"travel policy\"},{\"id\":\"1\",\"text\":\"volunteer policy\"}]}");
    }

    @Test
    void givesUpAfterTheRerankTimeoutInsteadOfTheLongEmbeddingTimeout() {
        response.set("{\"model\":\"m\",\"revision\":\"r7\",\"scores\":[{\"id\":\"0\",\"score\":1.0}]}");
        delayMillis = 1500;
        long started = System.nanoTime();

        assertThatThrownBy(() -> client(Duration.ofMillis(200)).score("q", List.of("p"))).isInstanceOf(RestClientException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1200));
    }

    @Test
    void rejectsAResponseThatDoesNotScoreEveryPassage() {
        response.set("{\"model\":\"m\",\"revision\":\"r7\",\"scores\":[{\"id\":\"0\",\"score\":1.0}]}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).score("q", List.of("a", "b"))).isInstanceOf(RestClientException.class)
                .hasMessageContaining("1 scores for 2 passages");
    }

    private ModelServiceRerankClient client(Duration rerankTimeout) {
        var properties = new ModelServiceProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "embedder", 2, Duration.ofSeconds(1),
                Duration.ofSeconds(30), "m", rerankTimeout);
        return new ModelServiceRerankClient(RestClient.builder(), properties);
    }
}

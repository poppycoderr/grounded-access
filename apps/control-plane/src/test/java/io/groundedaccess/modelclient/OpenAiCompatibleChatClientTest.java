package io.groundedaccess.modelclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleChatClientTest {

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(exchange.getRequestHeaders().getFirst("Authorization") + "|" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"model\":\"local-model:1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"answerable\\\": false}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
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
    void sendsADeterministicJsonRequestAndReturnsTheContentWithTheModelThatProducedIt() {
        var client = new OpenAiCompatibleChatClient(RestClient.builder(), properties(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), "example_key"));

        ChatReply reply = client.complete("system rules", "question and evidence");

        assertThat(client.isConfigured()).isTrue();
        assertThat(reply).isEqualTo(new ChatReply("local-model:1", "{\"answerable\": false}"));
        assertThat(requests).containsExactly("Bearer example_key|{\"model\":\"local-model\",\"messages\":[{\"role\":\"system\",\"content\":\"system rules\"},"
                + "{\"role\":\"user\",\"content\":\"question and evidence\"}],\"temperature\":0,\"response_format\":{\"type\":\"json_object\"}}");
    }

    @Test
    void isNotConfiguredWithoutABaseUrl() {
        assertThat(new OpenAiCompatibleChatClient(RestClient.builder(), properties(null, null)).isConfigured()).isFalse();
        assertThat(new OpenAiCompatibleChatClient(RestClient.builder(), properties(URI.create(""), null)).isConfigured()).isFalse();
    }

    private static ChatProperties properties(URI baseUrl, String apiKey) {
        return new ChatProperties(baseUrl, "local-model", apiKey, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
}

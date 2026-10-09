package io.groundedaccess;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.groundedaccess.modelclient.InputType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A model service and a chat model on one local port, speaking the real HTTP contracts, whose endpoints can be made to hang, fail or
 * answer nonsense. It lets failure tests run the real HTTP clients with their real timeouts instead of replacing them.
 */
public final class StubModelServer {

    public static final String EMBED = "/v1/embed";

    public static final String RERANK = "/v1/rerank";

    public static final String CHAT = "/v1/chat/completions";

    public enum Behaviour {
        OK, HANG, ERROR, GARBAGE
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();

    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    private final HashingEmbeddingClient hashing = new HashingEmbeddingClient();

    private final int port;

    private volatile String chatContent = "{\"answerable\": false}";

    private volatile HttpServer server;

    public StubModelServer() {
        this.server = bind(0);
        this.port = server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public void behave(String endpoint, Behaviour behaviour) {
        behaviours.put(endpoint, behaviour);
    }

    public void chatReplies(String content) {
        this.chatContent = content;
    }

    public int calls(String endpoint) {
        return calls.computeIfAbsent(endpoint, key -> new AtomicInteger()).get();
    }

    /**
     * Everything answers normally again, on a listening port, with the call counters at zero.
     */
    public void reset() {
        behaviours.clear();
        calls.clear();
        chatContent = "{\"answerable\": false}";
        if (server == null) {
            server = bind(port);
        }
    }

    /**
     * Closes the port, so connections are refused as they are when the service is not running.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private HttpServer bind(int onPort) {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", onPort), 0);
            created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            created.createContext(EMBED, exchange -> handle(exchange, EMBED));
            created.createContext(RERANK, exchange -> handle(exchange, RERANK));
            created.createContext(CHAT, exchange -> handle(exchange, CHAT));
            created.start();
            return created;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handle(HttpExchange exchange, String endpoint) throws IOException {
        calls.computeIfAbsent(endpoint, key -> new AtomicInteger()).incrementAndGet();
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        switch (behaviours.getOrDefault(endpoint, Behaviour.OK)) {
            case HANG -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, answer(endpoint, request));
            }
            case ERROR -> respond(exchange, 500, "{\"detail\":\"internal error\"}");
            case GARBAGE -> respond(exchange, 200, "<html>not the contract</html>");
            case OK -> respond(exchange, 200, answer(endpoint, request));
        }
    }

    private String answer(String endpoint, JsonNode request) {
        return switch (endpoint) {
            case EMBED -> {
                List<String> texts = new ArrayList<>();
                request.path("texts").forEach(text -> texts.add(text.asString()));
                List<float[]> vectors = hashing.embed(texts, InputType.QUERY).vectors();
                yield JSON.writeValueAsString(Map.of("model", request.path("model").asString(), "revision", "stub", "dimensions", 384, "vectors", vectors));
            }
            case RERANK -> {
                List<Map<String, Object>> scores = new ArrayList<>();
                request.path("passages").forEach(passage -> scores.add(Map.of("id", passage.path("id").asString(), "score", 1.0)));
                yield JSON.writeValueAsString(Map.of("model", request.path("model").asString(), "revision", "stub", "scores", scores));
            }
            default -> JSON.writeValueAsString(
                    Map.of("model", "stub-chat", "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", chatContent)))));
        };
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // The client gave up waiting and closed the connection, which is what a hanging endpoint is for.
        }
    }
}

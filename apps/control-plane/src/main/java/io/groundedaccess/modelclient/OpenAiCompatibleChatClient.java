package io.groundedaccess.modelclient;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link ChatClient} for any server that implements the OpenAI chat-completions API. Requests use temperature 0 and ask for a JSON object, so
 * the same evidence gives the same kind of reply as far as the model allows.
 */
@Component
@EnableConfigurationProperties(ChatProperties.class)
public class OpenAiCompatibleChatClient implements ChatClient {

    private final @Nullable RestClient http;

    private final ChatProperties properties;

    public OpenAiCompatibleChatClient(RestClient.Builder builder, ChatProperties properties) {
        this.properties = properties;
        if (properties.baseUrl() == null || properties.baseUrl().toString().isBlank() || properties.model() == null || properties.model().isBlank()) {
            this.http = null;
            return;
        }
        HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(properties.connectTimeout(), properties.readTimeout());
        var requestFactory = ClientHttpRequestFactoryBuilder.jdk().withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1)).build(settings);
        RestClient.Builder configured = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory);
        String apiKey = properties.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            configured = configured.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.http = configured.build();
    }

    @Override
    public boolean isConfigured() {
        return http != null;
    }

    @Override
    public ChatReply complete(String systemPrompt, String userPrompt) {
        RestClient client = Objects.requireNonNull(http, "no chat model is configured");
        String model = Objects.requireNonNull(properties.model());
        var request = new ChatRequest(model, List.of(new Message("system", systemPrompt), new Message("user", userPrompt)), 0, new ResponseFormat("json_object"));
        ChatResponse response = Objects.requireNonNull(client.post().uri("/chat/completions").body(request).retrieve().body(ChatResponse.class));
        if (response.choices() == null || response.choices().isEmpty() || response.choices().getFirst().message() == null) {
            throw new RestClientException("the chat endpoint returned no choice");
        }
        String content = response.choices().getFirst().message().content();
        return new ChatReply(response.model() != null ? response.model() : model, content == null ? "" : content);
    }

    record Message(
            String role,

            @Nullable String content) {
    }

    record ResponseFormat(
            String type) {
    }

    record ChatRequest(
            String model,

            List<Message> messages,

            int temperature,

            ResponseFormat response_format) {
    }

    record Choice(
            @Nullable Message message) {
    }

    record ChatResponse(
            @Nullable String model,

            @Nullable List<Choice> choices) {
    }
}

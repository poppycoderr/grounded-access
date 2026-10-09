package io.groundedaccess.modelclient;

/**
 * The text a chat model returned, with the model that produced it.
 */
public record ChatReply(
        String model,

        String content) {
}

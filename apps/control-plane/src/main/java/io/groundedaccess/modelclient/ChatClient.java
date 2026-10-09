package io.groundedaccess.modelclient;

/**
 * Generates text from a prompt made of authorized evidence. Implementations must never receive tenant or principal data. Generation is
 * optional: a deployment without a chat model answers with evidence only.
 */
public interface ChatClient {

    /**
     * False when no chat model is configured; callers then skip generation instead of treating it as a failure.
     */
    boolean isConfigured();

    ChatReply complete(String systemPrompt, String userPrompt);
}

package io.groundedaccess.modelclient;

import java.util.List;

/**
 * Turns authorized text into vectors. Implementations must never receive tenant or principal data.
 */
public interface EmbeddingClient {

    Embeddings embed(List<String> texts, InputType inputType);
}

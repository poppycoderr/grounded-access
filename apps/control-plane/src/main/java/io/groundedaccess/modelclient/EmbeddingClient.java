package io.groundedaccess.modelclient;

import java.util.List;

/**
 * Turns authorized text into vectors. Implementations must never receive tenant or principal data.
 */
public interface EmbeddingClient {

    Embeddings embed(List<String> texts, InputType inputType);

    /**
     * Name of the model this client produces vectors with, without a revision. Vectors from two models are not comparable, so callers use it to
     * detect a corpus that was embedded with something else.
     */
    String modelName();
}

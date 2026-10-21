package io.groundedaccess.modelclient;

import java.time.Duration;
import java.util.List;

/**
 * Turns authorized text into vectors. Implementations must never receive tenant or principal data.
 */
public interface EmbeddingClient {

    Embeddings embed(List<String> texts, InputType inputType);

    /**
     * As {@link #embed(List, InputType)}, telling the model service how long the caller still waits, so work it cannot start in time is not
     * done at all (ADR-0010).
     */
    default Embeddings embed(List<String> texts, InputType inputType, Duration remaining) {
        return embed(texts, inputType);
    }

    /**
     * How long a call of this kind may take at most; a caller with less time left does not start it.
     */
    default Duration timeout(InputType inputType) {
        return Duration.ZERO;
    }

    /**
     * How many leading characters of each passage text fit in {@code budget} tokens under the tokenizers of both models, special tokens
     * included (ADR-0009).
     */
    List<Integer> fittingChars(List<String> texts, int budget);

    /**
     * Name of the model this client produces vectors with, without a revision. Vectors from two models are not comparable, so callers use it to
     * detect a corpus that was embedded with something else.
     */
    String modelName();
}

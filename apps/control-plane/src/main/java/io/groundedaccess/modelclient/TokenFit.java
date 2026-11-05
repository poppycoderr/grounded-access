package io.groundedaccess.modelclient;

import java.util.List;

/**
 * How many leading Unicode code points of each text fit a token budget, and the identity of the tokenizers that counted them (ADR-0012).
 * Chunks cut by two different identities are not cut alike, so the identity is part of the index profile.
 */
public record TokenFit(
        String tokenizer,

        List<Integer> fittingChars) {
}

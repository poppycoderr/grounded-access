package io.groundedaccess.answering;

import java.util.List;

/**
 * One sentence of an answer with the evidence ids that support it. Only statements whose every citation names evidence that was in the prompt
 * are ever returned.
 */
public record Statement(
        String text,

        List<String> citations) {
}

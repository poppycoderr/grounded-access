package io.groundedaccess.answering;

import java.util.Locale;

/**
 * Outcome of a query. {@code NO_ANSWER} is the same whether nothing relevant exists or everything relevant is hidden from the principal.
 * {@code EVIDENCE_ONLY} means no answer was generated: no chat model is configured, or generation failed.
 */
public enum AnswerStatus {
    ANSWERED,
    NO_ANSWER,
    EVIDENCE_ONLY;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}

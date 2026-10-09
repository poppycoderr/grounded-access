package io.groundedaccess.answering;

import io.groundedaccess.retrieval.RetrievalResult;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The result of a query: its status, the validated statements, the evidence they cite and how retrieval produced that evidence.
 * {@code degraded} extends the retrieval's own list with what went wrong during generation. {@code chatModel} is null when no model was
 * called.
 */
public record Answer(
        AnswerStatus status,

        List<Statement> statements,

        List<Evidence> evidence,

        List<String> degraded,

        RetrievalResult retrieval,

        @Nullable String chatModel,

        String promptVersion) {
}

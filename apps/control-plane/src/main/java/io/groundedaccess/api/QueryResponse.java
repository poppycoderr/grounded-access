package io.groundedaccess.api;

import io.groundedaccess.answering.Answer;
import io.groundedaccess.answering.Evidence;
import io.groundedaccess.answering.InjectionHeuristic;
import io.groundedaccess.answering.Statement;
import io.groundedaccess.retrieval.Scope;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An answer, a refusal or evidence. {@code status} is {@code answered}, {@code no_answer} or {@code evidence_only}. Every citation in
 * {@code statements} names an entry of {@code evidence}; a {@code no_answer} has neither.
 */
public record QueryResponse(
        String status,

        List<Statement> statements,

        List<EvidenceResponse> evidence,

        List<String> degraded,

        String policyVersion,

        String planHash,

        Scope scope,

        UUID executionId,

        String traceId,

        @Nullable String chatModel,

        String promptVersion) {

    /**
     * A passage the answer may cite, with where it comes from. {@code risk} carries {@code instruction_like} when a keyword heuristic thinks
     * the passage addresses a language model; it is a label, not a filter.
     */
    public record EvidenceResponse(
            String id,

            String documentKey,

            int versionNo,

            String title,

            String sectionPath,

            int charStart,

            int charEnd,

            String text,

            List<String> risk) {

        static EvidenceResponse from(Evidence evidence) {
            var chunk = evidence.chunk();
            return new EvidenceResponse(evidence.id(), chunk.documentKey(), chunk.versionNo(), chunk.title(), chunk.sectionPath(), chunk.charStart(),
                    chunk.charEnd(), chunk.content(), InjectionHeuristic.looksLikeInstruction(chunk.content()) ? List.of(InjectionHeuristic.FLAG) : List.of());
        }
    }

    static QueryResponse from(Answer answer) {
        var retrieval = answer.retrieval();
        return new QueryResponse(answer.status().wireName(), answer.statements(), answer.evidence().stream().map(EvidenceResponse::from).toList(),
                answer.degraded(), retrieval.policyVersion(), retrieval.plan().hash(), retrieval.scope(), retrieval.executionId(), retrieval.traceId(),
                answer.chatModel(), answer.promptVersion());
    }
}

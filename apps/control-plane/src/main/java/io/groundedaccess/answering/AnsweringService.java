package io.groundedaccess.answering;

import io.groundedaccess.audit.AuditTrail;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.modelclient.ChatClient;
import io.groundedaccess.modelclient.ChatReply;
import io.groundedaccess.retrieval.RetrievalResult;
import io.groundedaccess.retrieval.RetrievalService;
import io.groundedaccess.retrieval.RetrievalStrategy;
import io.groundedaccess.retrieval.Scope;
import io.groundedaccess.telemetry.TraceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Answers a question from evidence the principal is authorized to read. Retrieval decides what the chat model may see; this class never
 * widens it. The model's reply is validated before anything is returned: a statement is kept only if every citation names a passage that was in
 * the prompt.
 */
@Service
@EnableConfigurationProperties(AnsweringProperties.class)
public class AnsweringService {

    public static final String GENERATION_UNAVAILABLE = "generation_unavailable";

    public static final String GENERATION_INVALID = "generation_invalid";

    private static final Logger log = LoggerFactory.getLogger(AnsweringService.class);

    private final RetrievalService retrieval;

    private final ChatClient chat;

    private final AuditTrail audit;

    private final AnsweringProperties properties;

    private final ContextBuilder contextBuilder;

    public AnsweringService(RetrievalService retrieval, ChatClient chat, AuditTrail audit, AnsweringProperties properties) {
        this.retrieval = retrieval;
        this.chat = chat;
        this.audit = audit;
        this.properties = properties;
        this.contextBuilder = new ContextBuilder(properties.contextMaxWords());
    }

    /**
     * The outcomes, in the order they are decided:
     * <ul>
     * <li>no evidence: {@code NO_ANSWER}, and the chat model is not called;</li>
     * <li>no chat model configured: {@code EVIDENCE_ONLY};</li>
     * <li>the chat call fails, or its reply is not the expected JSON: {@code EVIDENCE_ONLY} with a degraded reason;</li>
     * <li>the model says the evidence does not answer the question, or no statement survives validation: {@code NO_ANSWER};</li>
     * <li>otherwise {@code ANSWERED}, with only the evidence that is actually cited.</li>
     * </ul>
     * A {@code NO_ANSWER} carries no evidence, so it looks the same whether the answer is hidden from the principal or does not exist.
     */
    public Answer answer(Principal principal, String question, @Nullable RetrievalStrategy strategy, Scope scope) {
        RetrievalResult retrieved = retrieval.search(principal, question, strategy != null ? strategy : properties.strategy(), properties.evidenceLimit(), scope);
        List<Evidence> evidence = contextBuilder.build(retrieved.chunks());
        Generation generation = generate(question, evidence);
        List<String> degraded = new ArrayList<>(retrieved.degraded());
        if (generation.degraded() != null) {
            degraded.add(generation.degraded());
        }
        Set<String> cited = generation.statements().stream().flatMap(statement -> statement.citations().stream()).collect(Collectors.toSet());
        List<Evidence> shown = switch (generation.status()) {
            case ANSWERED -> evidence.stream().filter(item -> cited.contains(item.id())).toList();
            case EVIDENCE_ONLY -> evidence;
            case NO_ANSWER -> List.of();
        };
        audit.recordAnswer(principal, TraceContext.current(), retrieved.executionId(), retrieved.policyVersion(), generation.status().wireName(),
                evidence.size(), generation.statements().size(), generation.rejected(), generation.model(), AnswerPrompt.VERSION);
        return new Answer(generation.status(), generation.statements(), shown, List.copyOf(degraded), retrieved);
    }

    private record Generation(
            AnswerStatus status,

            List<Statement> statements,

            int rejected,

            @Nullable String model,

            @Nullable String degraded) {
    }

    private Generation generate(String question, List<Evidence> evidence) {
        if (evidence.isEmpty()) {
            return new Generation(AnswerStatus.NO_ANSWER, List.of(), 0, null, null);
        }
        if (!chat.isConfigured()) {
            return new Generation(AnswerStatus.EVIDENCE_ONLY, List.of(), 0, null, null);
        }
        ChatReply reply;
        try {
            reply = chat.complete(AnswerPrompt.SYSTEM, AnswerPrompt.user(question, evidence));
        } catch (RestClientException e) {
            log.warn("Generation failed, returning evidence only: {}", e.getClass().getSimpleName());
            return new Generation(AnswerStatus.EVIDENCE_ONLY, List.of(), 0, null, GENERATION_UNAVAILABLE);
        }
        Set<String> ids = evidence.stream().map(Evidence::id).collect(Collectors.toSet());
        Optional<AnswerParser.Parsed> parsed = AnswerParser.parse(reply.content(), ids);
        if (parsed.isEmpty()) {
            log.warn("The chat model's reply was not the expected JSON, returning evidence only");
            return new Generation(AnswerStatus.EVIDENCE_ONLY, List.of(), 0, reply.model(), GENERATION_INVALID);
        }
        AnswerParser.Parsed answer = parsed.get();
        if (!answer.answerable() || answer.statements().isEmpty()) {
            return new Generation(AnswerStatus.NO_ANSWER, List.of(), answer.rejected(), reply.model(), null);
        }
        return new Generation(AnswerStatus.ANSWERED, answer.statements(), answer.rejected(), reply.model(), null);
    }
}

package io.groundedaccess.answering;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the chat model's reply and keeps only what can be trusted. A statement survives only if it has text and at least one citation, and
 * every id it cites was in the prompt. The model's text is treated as untrusted input: nothing in it can add evidence, and a reply that is not
 * the expected JSON yields no parse at all.
 */
public final class AnswerParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AnswerParser() {
    }

    /**
     * What a reply contained after validation. {@code rejected} counts statements dropped for a missing or unknown citation.
     */
    public record Parsed(
            boolean answerable,

            List<Statement> statements,

            int rejected) {
    }

    /**
     * Empty when the reply is not a JSON object of the expected shape. Models sometimes wrap JSON in a Markdown code fence; that is removed
     * first.
     */
    public static Optional<Parsed> parse(String reply, Set<String> evidenceIds) {
        JsonNode root;
        try {
            root = JSON.readTree(unfence(reply));
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || !root.path("answerable").isBoolean() || !(root.path("statements").isArray() || root.path("statements").isMissingNode())) {
            return Optional.empty();
        }
        List<Statement> statements = new ArrayList<>();
        int rejected = 0;
        for (JsonNode node : root.path("statements")) {
            String text = node.path("text").isString() ? node.path("text").stringValue().strip() : "";
            Set<String> citations = new LinkedHashSet<>();
            boolean valid = !text.isEmpty() && node.path("citations").isArray() && !node.path("citations").isEmpty();
            for (JsonNode citation : node.path("citations")) {
                if (citation.isString() && evidenceIds.contains(citation.stringValue())) {
                    citations.add(citation.stringValue());
                } else {
                    valid = false;
                }
            }
            if (valid) {
                statements.add(new Statement(text, List.copyOf(citations)));
            } else {
                rejected++;
            }
        }
        return Optional.of(new Parsed(root.path("answerable").booleanValue(), List.copyOf(statements), rejected));
    }

    private static String unfence(String reply) {
        String text = reply.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine > 0 && closing > firstLine) {
                return text.substring(firstLine + 1, closing).strip();
            }
        }
        return text;
    }
}

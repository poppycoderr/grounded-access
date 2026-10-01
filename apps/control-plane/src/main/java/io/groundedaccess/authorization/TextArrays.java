package io.groundedaccess.authorization;

import java.util.Collection;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds PostgreSQL {@code text[]} literals. A literal is bound as one parameter and cast to {@code text[]} in the statement, so the values
 * never become part of the SQL text.
 */
public final class TextArrays {

    private TextArrays() {
    }

    /**
     * Every element is quoted, so commas, braces, quotes and backslashes inside a value stay data. Elements are sorted for a stable result.
     */
    public static String literal(Collection<String> values) {
        return new TreeSet<>(values).stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}

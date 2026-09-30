package io.groundedaccess.corpus;

import java.util.Locale;

/**
 * How a document's text is structured. Markdown is split on headings first; plain text has no structure beyond paragraphs, so a line starting
 * with {@code #} is ordinary text.
 */
public enum DocumentFormat {
    MARKDOWN,
    TEXT;

    public String column() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DocumentFormat fromColumn(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}

package io.groundedaccess.retrieval;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Position in the stable chunk order, rendered as {@code documentKey:versionNo:ordinal}. Document keys cannot contain ':', so the format is
 * unambiguous.
 */
public record ChunkCursor(
        String documentKey,

        int versionNo,

        int ordinal) {

    private static final Pattern FORMAT = Pattern.compile("([a-z0-9][a-z0-9._-]*):(\\d{1,9}):(\\d{1,9})");

    public static Optional<ChunkCursor> parse(String value) {
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ChunkCursor(matcher.group(1), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
    }

    @Override
    public String toString() {
        return documentKey + ":" + versionNo + ":" + ordinal;
    }
}

package io.groundedaccess.authorization;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Sensitivity levels in ascending order. A principal may read a document when the document's rank is at most the rank of the principal's
 * clearance.
 */
public enum Classification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED;

    public int rank() {
        return ordinal();
    }

    public String column() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Classification fromColumn(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * The clearance a token grants. A missing or unknown value grants the lowest level, so a mistyped claim can never widen access.
     */
    static Classification clearanceOf(@Nullable String claim) {
        if (claim != null) {
            for (Classification level : values()) {
                if (level.column().equals(claim)) {
                    return level;
                }
            }
        }
        return PUBLIC;
    }
}

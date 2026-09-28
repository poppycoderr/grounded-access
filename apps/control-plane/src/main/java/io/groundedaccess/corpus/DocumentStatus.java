package io.groundedaccess.corpus;

import java.util.Locale;

/**
 * Whether a document takes part in retrieval. Only {@code ACTIVE} documents do. {@code DELETED} is a tombstone: the row keeps the key so a
 * later ingestion can reuse it, and cleanup removes its versions and chunks.
 */
public enum DocumentStatus {
    ACTIVE,
    DISABLED,
    DELETED;

    String column() {
        return name().toLowerCase(Locale.ROOT);
    }

    static DocumentStatus fromColumn(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}

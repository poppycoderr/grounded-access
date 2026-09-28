package io.groundedaccess.corpus;

import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Disables, re-enables and deletes documents of one tenant. A change is a single row update, so it applies to the next query; the rows of a
 * deleted document are removed later by {@link CorpusCleanup}. A document of another tenant, or one already deleted, is reported as missing.
 */
@Service
public class DocumentLifecycle {

    private final CorpusWriter writer;

    public DocumentLifecycle(CorpusWriter writer) {
        this.writer = writer;
    }

    /**
     * Sets a document to {@code ACTIVE} or {@code DISABLED}. Use {@link #delete} to delete.
     */
    public Optional<DocumentState> setStatus(String tenantId, String key, DocumentStatus status) {
        if (status == DocumentStatus.DELETED) {
            throw new IllegalArgumentException("delete a document with delete(), not by setting its status");
        }
        return writer.changeStatus(tenantId, key, status);
    }

    public boolean delete(String tenantId, String key) {
        return writer.changeStatus(tenantId, key, DocumentStatus.DELETED).isPresent();
    }
}

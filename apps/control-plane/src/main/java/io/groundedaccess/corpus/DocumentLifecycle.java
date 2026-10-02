package io.groundedaccess.corpus;

import io.groundedaccess.audit.AuditTrail;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.telemetry.TraceContext;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disables, re-enables and deletes documents of the caller's tenant. A change is a single row update, so it applies to the next query; the rows
 * of a deleted document are removed later by {@link CorpusCleanup}. A document of another tenant, or one already deleted, is reported as missing.
 * The change and its audit event are one transaction: a change that cannot be audited does not happen.
 */
@Service
public class DocumentLifecycle {

    private final CorpusWriter writer;

    private final AuditTrail audit;

    private final TransactionTemplate transactions;

    public DocumentLifecycle(CorpusWriter writer, AuditTrail audit, TransactionTemplate transactions) {
        this.writer = writer;
        this.audit = audit;
        this.transactions = transactions;
    }

    /**
     * Sets a document to {@code ACTIVE} or {@code DISABLED}. Use {@link #delete} to delete.
     */
    public Optional<DocumentState> setStatus(Principal principal, String key, DocumentStatus status) {
        if (status == DocumentStatus.DELETED) {
            throw new IllegalArgumentException("delete a document with delete(), not by setting its status");
        }
        return change(principal, key, status, "document.status_change");
    }

    public boolean delete(Principal principal, String key) {
        return change(principal, key, DocumentStatus.DELETED, "document.delete").isPresent();
    }

    private Optional<DocumentState> change(Principal principal, String key, DocumentStatus status, String action) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            Optional<DocumentState> changed = writer.changeStatus(principal.tenantId(), key, status);
            changed.ifPresent(state -> audit.recordDocumentChange(principal, TraceContext.current(), action, state.key(), state.status().column()));
            return changed;
        }));
    }
}

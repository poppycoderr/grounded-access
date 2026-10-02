package io.groundedaccess.audit;

/**
 * The audit record could not be written, so the action it belongs to must not complete.
 */
public class AuditUnavailableException extends RuntimeException {

    public AuditUnavailableException(Throwable cause) {
        super("the audit record could not be written", cause);
    }
}

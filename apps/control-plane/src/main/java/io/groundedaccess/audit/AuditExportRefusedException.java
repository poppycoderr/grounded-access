package io.groundedaccess.audit;

/**
 * An audit export that was refused before anything was recorded: a cut-off that is too recent, or files that already exist. The message
 * names only the cut-off or the file and is safe to log.
 */
public class AuditExportRefusedException extends RuntimeException {

    public AuditExportRefusedException(String message) {
        super(message);
    }
}

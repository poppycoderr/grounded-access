package io.groundedaccess.api;

/**
 * No job with this id is visible to the caller, either because it does not exist or because it belongs to another tenant.
 */
class JobNotFoundException extends RuntimeException {

    JobNotFoundException() {
        super("ingestion job not found");
    }
}

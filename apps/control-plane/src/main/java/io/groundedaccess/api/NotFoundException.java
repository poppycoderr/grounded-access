package io.groundedaccess.api;

/**
 * The requested resource is not visible to the caller, either because it does not exist or because it belongs to another tenant. Both cases
 * produce the same response.
 */
class NotFoundException extends RuntimeException {

    NotFoundException(String detail) {
        super(detail);
    }
}

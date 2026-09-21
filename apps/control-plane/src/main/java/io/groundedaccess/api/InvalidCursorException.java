package io.groundedaccess.api;

/**
 * A listing cursor that was not produced by this API.
 */
class InvalidCursorException extends RuntimeException {

    InvalidCursorException(String cursor) {
        super("Invalid cursor: " + cursor);
    }
}

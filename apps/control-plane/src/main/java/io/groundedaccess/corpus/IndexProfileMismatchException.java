package io.groundedaccess.corpus;

/**
 * The model service produces vectors of another index profile than the stored ones, or than the ones it produced earlier in this process.
 * Such vectors are never compared with stored vectors and never written.
 */
public class IndexProfileMismatchException extends RuntimeException {

    public IndexProfileMismatchException(String message) {
        super(message);
    }
}

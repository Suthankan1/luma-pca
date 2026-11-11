package com.physicscube.luma.zoom;

/**
 * The receipt can never be processed as it is (for example a required field is
 * missing), so it goes straight to the dead letter instead of being retried. The
 * message must describe the problem without quoting payload content.
 */
public class PermanentProcessingException extends RuntimeException {
    public PermanentProcessingException(String message) {
        super(message);
    }
}

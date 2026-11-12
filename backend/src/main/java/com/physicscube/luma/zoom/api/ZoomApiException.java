package com.physicscube.luma.zoom.api;

import java.time.Duration;

/** A Zoom API call failed. The message holds the HTTP status and Zoom's error code, never request data. */
public class ZoomApiException extends RuntimeException {
    private final int status;
    private final Duration retryAfter;

    public ZoomApiException(String message, int status, Duration retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    /** Set for 429 responses that say when to retry. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean rateLimited() {
        return status == 429;
    }

    public boolean notFound() {
        return status == 404;
    }
}

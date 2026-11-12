package com.physicscube.luma.shared.api;

/** A feature this deployment has not configured (such as email). The message is shown to the caller. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}

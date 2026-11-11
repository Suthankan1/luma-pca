package com.physicscube.luma.shared.api;

/** The request is valid but contradicts current state. The message is shown to the caller. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}

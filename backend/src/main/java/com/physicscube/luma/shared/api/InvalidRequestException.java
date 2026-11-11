package com.physicscube.luma.shared.api;

/** Input that passed shape validation but is still unacceptable. The message is shown to the caller. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}

package com.physicscube.luma.shared.api;

/**
 * Also used when a record exists in another academy or outside the caller's scope,
 * so responses do not reveal that it exists.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource) {
        super(resource + " not found");
    }
}

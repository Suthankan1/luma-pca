package com.physicscube.luma.identity;

public record AccessToken(String value, long expiresInSeconds) {
    @Override
    public String toString() {
        return "AccessToken[value=<redacted>, expiresInSeconds=" + expiresInSeconds + "]";
    }
}

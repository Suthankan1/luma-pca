package com.physicscube.luma.identity;

public record SessionTokens(String accessToken, long expiresInSeconds, String refreshToken, long refreshExpiresInSeconds) {
    @Override
    public String toString() {
        return "SessionTokens[<redacted>]";
    }
}

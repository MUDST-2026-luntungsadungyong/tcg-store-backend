package com.mythix.tcg.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long expirationMinutes) {

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "APP_JWT_SECRET must be set for the active profile (use the explicit dev profile for local development)"
            );
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must be at least 32 bytes");
        }
        if (expirationMinutes <= 0) {
            throw new IllegalStateException("APP_JWT_EXPIRATION_MINUTES must be greater than zero");
        }
    }
}

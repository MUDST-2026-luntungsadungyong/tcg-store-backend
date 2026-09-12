package com.mythix.tcg.auth.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JwtPropertiesTest {

    @Test
    void missingSecretFailsFast() {
        assertThatIllegalStateException()
            .isThrownBy(() -> new JwtProperties("", 60))
            .withMessageContaining("APP_JWT_SECRET must be set");
    }

    @Test
    void shortSecretFailsFast() {
        assertThatIllegalStateException()
            .isThrownBy(() -> new JwtProperties("too-short", 60))
            .withMessageContaining("at least 32 bytes");
    }

    @Test
    void nonPositiveExpirationFailsFast() {
        assertThatIllegalStateException()
            .isThrownBy(() -> new JwtProperties("test-only-secret-key-that-is-at-least-thirty-two-bytes-long", 0))
            .withMessageContaining("APP_JWT_EXPIRATION_MINUTES");
    }
}

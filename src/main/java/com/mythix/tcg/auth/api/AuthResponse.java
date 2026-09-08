package com.mythix.tcg.auth.api;

public record AuthResponse(String token, UserResponse user) {
    @Override
    public String toString() {
        return "AuthResponse[token=[REDACTED], user=" + user + "]";
    }
}

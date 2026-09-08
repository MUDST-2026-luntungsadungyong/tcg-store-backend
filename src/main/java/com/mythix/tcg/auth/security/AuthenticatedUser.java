package com.mythix.tcg.auth.security;

public record AuthenticatedUser(Long userId, String email, String role) {
}

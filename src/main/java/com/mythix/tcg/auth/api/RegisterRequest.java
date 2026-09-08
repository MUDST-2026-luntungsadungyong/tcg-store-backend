package com.mythix.tcg.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 8, max = 72) String password
) {
    @Override
    public String toString() {
        return "RegisterRequest[name=" + name + ", email=" + email + ", password=[REDACTED]]";
    }
}

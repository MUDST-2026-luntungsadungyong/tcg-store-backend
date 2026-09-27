package com.mythix.tcg.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 8, max = 72) String newPassword
) {
    @Override
    public String toString() {
        return "ResetPasswordRequest[email=" + email + ", newPassword=[REDACTED]]";
    }
}

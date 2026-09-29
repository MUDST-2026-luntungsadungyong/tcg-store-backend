package com.mythix.tcg.admin.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAdminRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 8, max = 72) String password
) {
    @Override
    public String toString() {
        return "CreateAdminRequest[name=" + name + ", email=" + email + ", password=[REDACTED]]";
    }
}

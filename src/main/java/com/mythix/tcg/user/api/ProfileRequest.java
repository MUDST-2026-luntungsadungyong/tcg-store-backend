package com.mythix.tcg.user.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record ProfileRequest(
    @Size(max = 255) String name,
    @Size(max = 50) String phone,
    @Size(max = 100) String line,
    @Size(max = 255) String facebook,
    @Email @Size(max = 255) String email,
    @Size(max = 500) String avatarUrl
) {}

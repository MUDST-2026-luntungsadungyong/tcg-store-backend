package com.mythix.tcg.seller.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SellerVerificationRequest(
    @NotBlank @Size(max = 255) String shopName,
    @Size(max = 5000) String shopDescription,
    @Size(max = 50) String phone,
    @Size(max = 100) String line,
    @Size(max = 255) String facebook
) {}

package com.mythix.tcg.seller.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SellerProductStatusRequest(
    @NotBlank(message = "Status is required")
    @Pattern(regexp = "SHOW|HIDE", message = "Status must be SHOW or HIDE")
    String status
) {
}

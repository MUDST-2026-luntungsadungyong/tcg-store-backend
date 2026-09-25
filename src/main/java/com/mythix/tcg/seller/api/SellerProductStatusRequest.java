package com.mythix.tcg.seller.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SellerProductStatusRequest(
    @NotBlank(message = "Status is required")
    @Pattern(regexp = "(?i)SHOW|HIDE|OUT_OF_STOCK", message = "Status must be SHOW, HIDE, or OUT_OF_STOCK")
    String status
) {
}

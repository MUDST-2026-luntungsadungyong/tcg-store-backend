package com.mythix.tcg.cart.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateCartItemRequest(
    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be greater than 0") Integer quantity
) {
}

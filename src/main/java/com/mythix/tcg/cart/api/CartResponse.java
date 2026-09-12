package com.mythix.tcg.cart.api;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
    List<CartItemResponse> items,
    int totalItems,
    BigDecimal totalAmount
) {
}

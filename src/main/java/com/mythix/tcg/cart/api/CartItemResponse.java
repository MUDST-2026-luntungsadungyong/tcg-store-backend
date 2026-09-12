package com.mythix.tcg.cart.api;

import java.math.BigDecimal;

public record CartItemResponse(
    String listingId,
    String sellerId,
    String name,
    String game,
    String image,
    BigDecimal price,
    String condition,
    String set,
    String rarity,
    int quantity,
    boolean available,
    int availableQuantity,
    BigDecimal subtotal
) {
}

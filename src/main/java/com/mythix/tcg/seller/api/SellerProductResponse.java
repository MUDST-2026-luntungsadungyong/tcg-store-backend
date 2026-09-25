package com.mythix.tcg.seller.api;

import java.math.BigDecimal;

public record SellerProductResponse(
    String id,
    String sellerId,
    String name,
    String game,
    String image,
    BigDecimal price,
    BigDecimal cost,
    int stock,
    String status,
    String condition,
    String language,
    String set,
    String rarity,
    long activeOrderCount,
    long activeReservationCount
) {
}

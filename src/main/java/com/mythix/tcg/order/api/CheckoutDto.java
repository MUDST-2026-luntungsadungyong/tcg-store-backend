package com.mythix.tcg.order.api;

import java.math.BigDecimal;
import java.util.List;

public final class CheckoutDto {
    private CheckoutDto() {
    }

    /** Checkout uses the authenticated user's current cart; no client fields are accepted. */
    public record CheckoutRequest() {
    }

    public record CheckoutItemResponse(
        String listingId,
        String sellerId,
        String name,
        BigDecimal price,
        int quantity,
        BigDecimal subtotal
    ) {
    }

    public record CheckoutResponse(
        String id,
        String orderNumber,
        String status,
        BigDecimal totalAmount,
        List<CheckoutItemResponse> items,
        String createdAt
    ) {
    }
}

package com.mythix.tcg.order.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public final class CheckoutDto {
    private CheckoutDto() {
    }

    /** Checkout uses the authenticated user's current cart; no client fields are accepted. */
    public static final class CheckoutRequest {
        private final Map<String, JsonNode> unexpectedFields = new LinkedHashMap<>();

        @JsonAnySetter
        public void rejectUnexpectedField(String name, JsonNode value) {
            unexpectedFields.put(name, value);
        }

        public void validate() {
            if (!unexpectedFields.isEmpty()) {
                throw new com.mythix.tcg.order.application.CheckoutValidationException(
                    unexpectedFields.keySet().iterator().next(),
                    "Checkout request does not accept client-supplied fields"
                );
            }
        }
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

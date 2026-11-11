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

    public static final class CheckoutRequest {
        private String voucherCode;
        private final Map<String, JsonNode> unexpectedFields = new LinkedHashMap<>();

        public String getVoucherCode() {
            return voucherCode;
        }

        public void setVoucherCode(String voucherCode) {
            this.voucherCode = voucherCode;
        }

        @JsonAnySetter
        public void rejectUnexpectedField(String name, JsonNode value) {
            if (!"voucherCode".equalsIgnoreCase(name)) {
                unexpectedFields.put(name, value);
            }
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
        BigDecimal subtotalAmount,
        BigDecimal discountAmount,
        BigDecimal platformFee,
        BigDecimal sellerNetAmount,
        String voucherCode,
        List<CheckoutItemResponse> items,
        String createdAt
    ) {
        public CheckoutResponse(
            String id,
            String orderNumber,
            String status,
            BigDecimal totalAmount,
            List<CheckoutItemResponse> items,
            String createdAt
        ) {
            this(id, orderNumber, status, totalAmount, totalAmount, BigDecimal.ZERO, BigDecimal.ZERO, totalAmount, null, items, createdAt);
        }
    }
}

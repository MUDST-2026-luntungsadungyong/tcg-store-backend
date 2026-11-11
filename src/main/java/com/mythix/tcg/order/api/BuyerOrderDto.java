package com.mythix.tcg.order.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mythix.tcg.cart.api.CartResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

public class BuyerOrderDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BuyerOrderItemListingDto {
        private String id;
        private String name;
        private String game;
        private String image;
        private BigDecimal price;
        private String sellerId;
        private String set;
        private String cardNumber;
        private String rarity;
        private String condition;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BuyerOrderItemResponse {
        private String itemId;
        private String productId;
        private String cardId;
        private String productName;
        private BigDecimal unitPrice;
        private Integer quantity;
        private BigDecimal itemTotal;
        private String imageUrl;
        private BuyerOrderItemListingDto listing;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OrderStatusLogResponse {
        private String fromStatus;
        private String toStatus;
        private String changedByRole;
        private String note;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BuyerOrderResponse {
        private String id;
        private String orderNumber;
        private String orderType;
        private String buyerId;
        private String sellerId;
        private String sellerName;
        private String sellerEmail;
        private String sellerPhone;
        private String sellerLine;
        private String sellerFacebook;
        private String sellerOtherContact;
        private String status;
        private String trackingNumber;
        private String shippingCarrier;
        private BigDecimal totalAmount;
        private BigDecimal totalPrice;
        private BigDecimal subtotalAmount;
        private BigDecimal discountAmount;
        private String voucherCode;
        private Integer itemCount;
        private List<BuyerOrderItemResponse> items;
        private List<OrderStatusLogResponse> logs;
        private String createdAt;
        private String updatedAt;
        private String paymentConfirmedAt;
        private String sentAt;
        private String ownershipTransferredAt;
        private String cancelledAt;
    }

    public record UpdateBuyerOrderStatusRequest(
        String status,
        String note
    ) {}

    public record ReorderItemResponse(
        String listingId,
        String productName,
        int requestedQuantity,
        int addedQuantity,
        int availableQuantity,
        String outcome
    ) {}

    public record ReorderResponse(
        CartResponse cart,
        List<ReorderItemResponse> items,
        int totalAddedItems,
        boolean hasUnavailableItems
    ) {}

    public record OrderVerificationResponse(
        String orderNumber,
        String status,
        String orderType,
        String updatedAt
    ) {}
}

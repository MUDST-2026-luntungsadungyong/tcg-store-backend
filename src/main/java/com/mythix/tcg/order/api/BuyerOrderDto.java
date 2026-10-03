package com.mythix.tcg.order.api;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        private String buyerId;
        private String sellerId;
        private String sellerName;
        private String status;
        private BigDecimal totalAmount;
        private BigDecimal totalPrice;
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
}

package com.mythix.tcg.order.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class SellerOrderDto {

    public record SellerOrderItemResponse(
        Long itemId,
        String cardId,
        String productName,
        BigDecimal unitPrice,
        BigDecimal unitCost,
        BigDecimal itemProfit,
        Integer quantity,
        BigDecimal itemTotal
    ) {}

    public record OrderStatusLogResponse(
        String fromStatus,
        String toStatus,
        String changedByRole,
        String note,
        LocalDateTime createdAt
    ) {}

    public record SellerOrderResponse(
        Long id,
        String orderNumber,
        String status,
        Long buyerId,
        String buyerName,
        String buyerEmail,
        String buyerPhone,
        String buyerContactLine,
        String buyerContactFacebook,
        BigDecimal sellerRevenue,
        BigDecimal sellerCost,
        BigDecimal sellerProfit,
        List<SellerOrderItemResponse> items,
        List<OrderStatusLogResponse> logs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record UpdateOrderStatusRequest(
        @NotBlank(message = "Status is required")
        @Pattern(regexp = "(?i)PENDING|CONFIRMED|SENT|FULFILLED|CANCELLED", message = "Status must be PENDING, CONFIRMED, SENT, FULFILLED, or CANCELLED")
        String status,
        String note
    ) {}

    public record SellerProfitSummaryResponse(
        BigDecimal totalRevenue,
        BigDecimal totalCost,
        BigDecimal netProfit,
        long totalOrders,
        long pendingOrders,
        long fulfilledOrders,
        long cancelledOrders
    ) {}
}

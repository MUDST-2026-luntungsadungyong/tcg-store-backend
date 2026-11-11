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
        String orderType,
        Long buyerId,
        String buyerName,
        String buyerEmail,
        String buyerPhone,
        String buyerContactLine,
        String buyerContactFacebook,
        BigDecimal sellerRevenue,
        BigDecimal sellerPlatformFee,
        BigDecimal sellerNetRevenue,
        BigDecimal sellerCost,
        BigDecimal sellerProfit,
        List<SellerOrderItemResponse> items,
        List<OrderStatusLogResponse> logs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record UpdateOrderStatusRequest(
        @NotBlank(message = "Status is required")
        @Pattern(regexp = "(?i)PENDING|CONFIRMED|AWAITING_BUYER_CONTACT|PENDING_SELLER_ACTION|SENT|FULFILLED|CANCELLED", message = "Status is not supported")
        String status,
        String note,
        String trackingNumber,
        Boolean noBuyerContact
    ) {}

    public record SellerProfitSummaryResponse(
        BigDecimal totalRevenue,
        BigDecimal totalPlatformFee,
        BigDecimal totalCost,
        BigDecimal netProfit,
        long totalOrders,
        long pendingOrders,
        long fulfilledOrders,
        long cancelledOrders
    ) {}

    public record SellerMonthlyPointResponse(
        int year,
        int month,
        BigDecimal grossSales,
        BigDecimal platformFee,
        BigDecimal netRevenue,
        BigDecimal totalCost,
        BigDecimal netProfit,
        long orderCount
    ) {}

    public record SellerMonthlyDashboardResponse(
        SellerMonthlyPointResponse selectedMonth,
        List<SellerMonthlyPointResponse> lastTwelveMonths
    ) {}
}

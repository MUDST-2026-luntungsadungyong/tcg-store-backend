package com.mythix.tcg.admin.api;
import com.mythix.tcg.order.domain.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record AdminOrderLogResponse(String id, String orderNumber, String buyerName, String buyerEmail, String status, BigDecimal totalAmount, LocalDateTime createdAt) {
 public static AdminOrderLogResponse from(Order order) { return new AdminOrderLogResponse(String.valueOf(order.getId()), order.getOrderNumber(), order.getBuyer().getFullName(), order.getBuyer().getEmail(), order.getStatus(), order.getTotalAmount(), order.getCreatedAt()); }
}

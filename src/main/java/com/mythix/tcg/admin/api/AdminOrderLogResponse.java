package com.mythix.tcg.admin.api;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record AdminOrderLogResponse(String id, String orderNumber, String buyerName, String buyerEmail, String status, BigDecimal totalAmount, LocalDateTime createdAt, BigDecimal platformFee, BigDecimal sellerNetAmount, List<AdminOrderLogItemResponse> items) {
 public static AdminOrderLogResponse from(Order order) { return new AdminOrderLogResponse(String.valueOf(order.getId()), order.getOrderNumber(), order.getBuyer().getFullName(), order.getBuyer().getEmail(), order.getStatus(), order.getTotalAmount(), order.getCreatedAt(), order.getPlatformFee(), order.getSellerNetAmount(), order.getItems().stream().map(AdminOrderLogItemResponse::from).toList()); }
 public record AdminOrderLogItemResponse(String id, String productName, String cardId, String sellerName,
                                         String sellerEmail, String shopName, BigDecimal price, Integer quantity,
                                         BigDecimal itemTotal) {
  static AdminOrderLogItemResponse from(OrderItem item) {
   BigDecimal price = item.getPriceAtPurchase();
   BigDecimal itemTotal = "AUCTION".equals(item.getOrder().getOrderType())
       ? price : price.multiply(BigDecimal.valueOf(item.getQuantity()));
   return new AdminOrderLogItemResponse(String.valueOf(item.getId()), item.getProductNameSnapshot(), item.getCardId(),
       item.getSeller().getFullName(), item.getSeller().getEmail(), item.getShopNameSnapshot(), price,
       item.getQuantity(), itemTotal);
  }
 }
}

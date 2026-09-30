package com.mythix.tcg.order.application;

import com.mythix.tcg.order.api.SellerOrderDto.*;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.notification.application.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SellerOrderService {

    private final OrderRepository orderRepository;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public Page<SellerOrderResponse> getSellerOrders(Long sellerId, String status, Pageable pageable) {
        Page<Order> orders;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            orders = orderRepository.findOrdersBySellerIdAndStatus(sellerId, normalizeStatus(status), pageable);
        } else {
            orders = orderRepository.findOrdersBySellerId(sellerId, pageable);
        }

        List<SellerOrderResponse> dtoList = orders.getContent().stream()
                .map(order -> mapToSellerOrderResponse(order, sellerId))
                .toList();

        return new PageImpl<>(dtoList, pageable, orders.getTotalElements());
    }

    @Transactional(readOnly = true)
    public SellerOrderResponse getSellerOrderById(Long sellerId, Long orderId) {
        Order order = orderRepository.findOrderForSeller(sellerId, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found for seller"));
        return mapToSellerOrderResponse(order, sellerId);
    }

    @Transactional
    public SellerOrderResponse updateOrderStatus(Long sellerId, Long orderId, String newStatus) {
        Order order = orderRepository.findOrderForSeller(sellerId, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found for seller"));

        String previousStatus = order.getStatus();
        String updatedStatus = normalizeStatus(newStatus);
        order.setStatus(updatedStatus);
        Order saved = orderRepository.save(order);
        // The current frontend maps its SENT action to the backend's FULFILLED status.
        // Notify only on the transition so retries do not create duplicate notifications.
        if (!"FULFILLED".equalsIgnoreCase(previousStatus)
                && "FULFILLED".equalsIgnoreCase(updatedStatus)) {
            notificationService.notifyBuyerOrderSent(saved);
        }
        return mapToSellerOrderResponse(saved, sellerId);
    }

    @Transactional(readOnly = true)
    public SellerProfitSummaryResponse getProfitSummary(Long sellerId) {
        List<Order> allOrders = orderRepository.findAllOrdersBySellerId(sellerId);

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        long totalOrders = allOrders.size();
        long pendingOrders = 0;
        long fulfilledOrders = 0;
        long cancelledOrders = 0;

        for (Order order : allOrders) {
            String status = order.getStatus();
            if ("PENDING".equalsIgnoreCase(status)) {
                pendingOrders++;
            } else if ("FULFILLED".equalsIgnoreCase(status)) {
                fulfilledOrders++;
            } else if ("CANCELLED".equalsIgnoreCase(status)) {
                cancelledOrders++;
            }

            if (!"CANCELLED".equalsIgnoreCase(status)) {
                for (OrderItem item : order.getItems()) {
                    if (item.getSeller() != null && item.getSeller().getId().equals(sellerId)) {
                        BigDecimal qty = BigDecimal.valueOf(item.getQuantity());
                        BigDecimal price = item.getPriceAtPurchase() != null ? item.getPriceAtPurchase() : BigDecimal.ZERO;
                        BigDecimal cost = item.getCostAtPurchase() != null ? item.getCostAtPurchase() : BigDecimal.ZERO;

                        totalRevenue = totalRevenue.add(price.multiply(qty));
                        totalCost = totalCost.add(cost.multiply(qty));
                    }
                }
            }
        }

        BigDecimal netProfit = totalRevenue.subtract(totalCost);

        return new SellerProfitSummaryResponse(
                totalRevenue,
                totalCost,
                netProfit,
                totalOrders,
                pendingOrders,
                fulfilledOrders,
                cancelledOrders
        );
    }

    private SellerOrderResponse mapToSellerOrderResponse(Order order, Long sellerId) {
        List<OrderItem> sellerItems = order.getItems().stream()
                .filter(item -> item.getSeller() != null && item.getSeller().getId().equals(sellerId))
                .toList();

        BigDecimal sellerRevenue = BigDecimal.ZERO;
        BigDecimal sellerCost = BigDecimal.ZERO;

        List<SellerOrderItemResponse> itemDtos = new ArrayList<>();
        for (OrderItem item : sellerItems) {
            BigDecimal qty = BigDecimal.valueOf(item.getQuantity());
            BigDecimal price = item.getPriceAtPurchase() != null ? item.getPriceAtPurchase() : BigDecimal.ZERO;
            BigDecimal cost = item.getCostAtPurchase() != null ? item.getCostAtPurchase() : BigDecimal.ZERO;
            BigDecimal itemTotal = price.multiply(qty);
            BigDecimal itemTotalCost = cost.multiply(qty);
            BigDecimal itemProfit = itemTotal.subtract(itemTotalCost);

            sellerRevenue = sellerRevenue.add(itemTotal);
            sellerCost = sellerCost.add(itemTotalCost);

            itemDtos.add(new SellerOrderItemResponse(
                    item.getId(),
                    item.getCardId(),
                    item.getProductNameSnapshot(),
                    price,
                    cost,
                    itemProfit,
                    item.getQuantity(),
                    itemTotal
            ));
        }

        BigDecimal sellerProfit = sellerRevenue.subtract(sellerCost);

        var buyer = order.getBuyer();
        return new SellerOrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                buyer != null ? buyer.getId() : null,
                buyer != null ? buyer.getFullName() : null,
                buyer != null ? buyer.getEmail() : null,
                buyer != null ? buyer.getPhone() : null,
                buyer != null ? buyer.getContactLine() : null,
                buyer != null ? buyer.getContactFacebook() : null,
                sellerRevenue,
                sellerCost,
                sellerProfit,
                itemDtos,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private String normalizeStatus(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "PAID", "FULFILLED", "CANCELLED").contains(value)) {
            throw new OrderStatusValidationException("status",
                "Status must be PENDING, PAID, FULFILLED, or CANCELLED");
        }
        return value;
    }
}

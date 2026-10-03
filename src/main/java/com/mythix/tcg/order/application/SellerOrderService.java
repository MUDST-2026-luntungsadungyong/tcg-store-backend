package com.mythix.tcg.order.application;

import com.mythix.tcg.order.api.SellerOrderDto.*;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.domain.OrderStatusLog;
import com.mythix.tcg.order.persistence.OrderStatusLogRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.persistence.UserRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SellerOrderService {

    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final ProductRepository productRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final UserRepository userRepository;

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
    public SellerOrderResponse updateOrderStatus(Long sellerId, Long orderId, String newStatus, String note) {
        Order order = orderRepository.findOrderForSeller(sellerId, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found for seller"));

        String previousStatus = order.getStatus();
        String updatedStatus = normalizeStatus(newStatus);

        if (previousStatus.equalsIgnoreCase(updatedStatus)) {
            return mapToSellerOrderResponse(order, sellerId);
        }

        validateStatusTransition(previousStatus, updatedStatus);

        if ("SENT".equalsIgnoreCase(updatedStatus)) {
            deductInventoryOnSend(order);
        } else if ("CANCELLED".equalsIgnoreCase(updatedStatus)) {
            releaseInventoryOnCancellation(order);
        }

        order.setStatus(updatedStatus);
        Order saved = orderRepository.save(order);

        OrderStatusLog log = OrderStatusLog.builder()
                .order(saved)
                .fromStatus(previousStatus)
                .toStatus(updatedStatus)
                .changedBy(userRepository.getReferenceById(sellerId))
                .changedByRole("SELLER")
                .note(note)
                .build();
        orderStatusLogRepository.save(log);

        if ("SENT".equalsIgnoreCase(updatedStatus)) {
            notificationService.notifyBuyerOrderSent(saved);
        }
        return mapToSellerOrderResponse(saved, sellerId);
    }

    private void validateStatusTransition(String from, String to) {
        if ("FULFILLED".equalsIgnoreCase(from) || "CANCELLED".equalsIgnoreCase(from)) {
            throw new OrderStatusValidationException("status",
                    "Cannot change status of an order that is already " + from);
        }
        if ("CANCELLED".equalsIgnoreCase(to)) {
            if (!"PENDING".equalsIgnoreCase(from) && !"CONFIRMED".equalsIgnoreCase(from)) {
                throw new OrderStatusValidationException("status", "Can only cancel PENDING or CONFIRMED orders");
            }
        } else if ("CONFIRMED".equalsIgnoreCase(to)) {
            if (!"PENDING".equalsIgnoreCase(from)) {
                throw new OrderStatusValidationException("status", "Can only confirm PENDING orders");
            }
        } else if ("SENT".equalsIgnoreCase(to)) {
            if (!"CONFIRMED".equalsIgnoreCase(from)) {
                throw new OrderStatusValidationException("status", "Can only send CONFIRMED orders");
            }
        } else if ("FULFILLED".equalsIgnoreCase(to)) {
            throw new OrderStatusValidationException("status", "Only the buyer can fulfill the order");
        } else if ("PENDING".equalsIgnoreCase(to)) {
            throw new OrderStatusValidationException("status", "Cannot revert order to PENDING");
        }
    }

    private void deductInventoryOnSend(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return;
        }

        List<Long> productIds = order.getItems().stream()
                .filter(item -> item.getProduct() != null && item.getProduct().getId() != null)
                .map(item -> item.getProduct().getId())
                .sorted()
                .distinct()
                .toList();

        Map<Long, Product> productsById = new HashMap<>();
        for (Long productId : productIds) {
            productRepository.findByIdForUpdate(productId).ifPresent(p -> productsById.put(p.getId(), p));
        }

        for (OrderItem item : order.getItems()) {
            if (item.getProduct() == null) {
                continue;
            }
            Product product = productsById.getOrDefault(item.getProduct().getId(), item.getProduct());
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            int onHand = product.getOnHand() != null ? product.getOnHand() : 0;
            int reserved = product.getReserved() != null ? product.getReserved() : 0;

            int newOnHand = Math.max(0, onHand - qty);
            int newReserved = Math.max(0, reserved - qty);
            newReserved = Math.min(newReserved, newOnHand);

            product.setOnHand(newOnHand);
            product.setReserved(newReserved);
            productRepository.save(product);
        }
    }

    private void releaseInventoryOnCancellation(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return;
        }

        List<Long> productIds = order.getItems().stream()
                .filter(item -> item.getProduct() != null && item.getProduct().getId() != null)
                .map(item -> item.getProduct().getId())
                .sorted()
                .distinct()
                .toList();

        Map<Long, Product> productsById = new HashMap<>();
        for (Long productId : productIds) {
            productRepository.findByIdForUpdate(productId).ifPresent(p -> productsById.put(p.getId(), p));
        }

        for (OrderItem item : order.getItems()) {
            if (item.getProduct() == null) {
                continue;
            }
            Product product = productsById.getOrDefault(item.getProduct().getId(), item.getProduct());
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            int onHand = product.getOnHand() != null ? product.getOnHand() : 0;
            int reserved = product.getReserved() != null ? product.getReserved() : 0;

            int newReserved = Math.max(0, reserved - qty);
            newReserved = Math.min(newReserved, onHand);

            product.setReserved(newReserved);
            productRepository.save(product);
        }
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
            if ("PENDING".equalsIgnoreCase(status) || "PAID".equalsIgnoreCase(status)) {
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

        List<OrderStatusLogResponse> logs = orderStatusLogRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())
                .stream()
                .map(log -> new OrderStatusLogResponse(
                        log.getFromStatus(),
                        log.getToStatus(),
                        log.getChangedByRole(),
                        log.getNote(),
                        log.getCreatedAt()
                ))
                .toList();

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
                logs,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private String normalizeStatus(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "CONFIRMED", "SENT", "FULFILLED", "CANCELLED").contains(value)) {
            throw new OrderStatusValidationException("status",
                "Status must be PENDING, CONFIRMED, SENT, FULFILLED, or CANCELLED");
        }
        return value;
    }
}

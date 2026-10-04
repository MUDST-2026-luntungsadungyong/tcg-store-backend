package com.mythix.tcg.order.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderItemListingDto;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderItemResponse;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderResponse;
import com.mythix.tcg.order.api.BuyerOrderDto.OrderStatusLogResponse;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.order.domain.OrderStatusLog;
import com.mythix.tcg.order.persistence.OrderStatusLogRepository;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.collection.application.CollectionService;
import com.mythix.tcg.collection.api.CollectionDto.AddToCollectionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class BuyerOrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CollectionService collectionService;

    @Transactional(readOnly = true)
    public List<BuyerOrderResponse> getBuyerOrders(Long buyerId, String status) {
        List<Order> orders;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            orders = orderRepository.findByBuyerIdAndStatusOrderByCreatedAtDesc(
                buyerId, normalizeStatus(status));
        } else {
            orders = orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId);
        }

        return orders.stream()
                .map(this::mapToBuyerOrderResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<BuyerOrderResponse> getBuyerOrdersPaged(Long buyerId, String status, Pageable pageable) {
        Page<Order> orders;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            orders = orderRepository.findByBuyerIdAndStatusOrderByCreatedAtDesc(
                buyerId, normalizeStatus(status), pageable);
        } else {
            orders = orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId, pageable);
        }

        return orders.map(this::mapToBuyerOrderResponse);
    }

    @Transactional(readOnly = true)
    public BuyerOrderResponse getBuyerOrderById(Long buyerId, Long orderId) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        return mapToBuyerOrderResponse(order);
    }

    @Transactional
    public BuyerOrderResponse updateOrderStatus(Long buyerId, Long orderId, String newStatus, String note) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        String previousStatus = order.getStatus();
        String updatedStatus = normalizeStatus(newStatus);

        if (previousStatus.equalsIgnoreCase(updatedStatus)) {
            return mapToBuyerOrderResponse(order);
        }

        validateStatusTransition(previousStatus, updatedStatus);

        if ("FULFILLED".equalsIgnoreCase(updatedStatus)) {
            transferOwnershipToBuyer(order);
        } else if ("CANCELLED".equalsIgnoreCase(updatedStatus)) {
            releaseInventoryOnCancellation(order);
        }

        order.setStatus(updatedStatus);
        Order saved = orderRepository.save(order);

        OrderStatusLog log = OrderStatusLog.builder()
                .order(saved)
                .fromStatus(previousStatus)
                .toStatus(updatedStatus)
                .changedBy(userRepository.getReferenceById(buyerId))
                .changedByRole("BUYER")
                .note(note)
                .build();
        orderStatusLogRepository.save(log);

        return mapToBuyerOrderResponse(saved);
    }

    private void validateStatusTransition(String from, String to) {
        if ("FULFILLED".equalsIgnoreCase(from) || "CANCELLED".equalsIgnoreCase(from)) {
            throw new OrderStatusValidationException("status", "Cannot change status of an order that is already " + from);
        }
        if ("CANCELLED".equalsIgnoreCase(to)) {
            if (!"PENDING".equalsIgnoreCase(from) && !"CONFIRMED".equalsIgnoreCase(from)) {
                throw new OrderStatusValidationException("status", "Can only cancel PENDING or CONFIRMED orders");
            }
        } else if ("FULFILLED".equalsIgnoreCase(to)) {
            if (!"SENT".equalsIgnoreCase(from)) {
                throw new OrderStatusValidationException("status", "Can only fulfill SENT orders");
            }
        } else if ("CONFIRMED".equalsIgnoreCase(to) || "SENT".equalsIgnoreCase(to)) {
            throw new OrderStatusValidationException("status", "Only seller can confirm or send the order");
        }
    }

    private void transferOwnershipToBuyer(Order order) {
        if (order.getItems() == null) return;
        for (OrderItem item : order.getItems()) {
            if (item.getProduct() != null) {
                AddToCollectionRequest request = AddToCollectionRequest.builder()
                        .listingId(String.valueOf(item.getProduct().getId()))
                        .condition(item.getProduct().getCondition())
                        .quantity(item.getQuantity())
                        .build();
                collectionService.addToCollection(order.getBuyer().getId(), request);
            }
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

    private BuyerOrderResponse mapToBuyerOrderResponse(Order order) {
        List<OrderItem> items = order.getItems() != null ? order.getItems() : Collections.emptyList();

        int totalCount = items.stream()
                .mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum();

        String sellerId = null;
        String sellerName = null;
        if (!items.isEmpty() && items.get(0).getSeller() != null) {
            sellerId = String.valueOf(items.get(0).getSeller().getId());
            sellerName = items.get(0).getShopNameSnapshot();
        }

        List<BuyerOrderItemResponse> itemResponses = items.stream()
                .map(this::mapToBuyerOrderItemResponse)
                .toList();

        String createdAt = order.getCreatedAt() != null ? order.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
        String updatedAt = order.getUpdatedAt() != null ? order.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;

        List<OrderStatusLogResponse> logs = orderStatusLogRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())
                .stream()
                .map(log -> OrderStatusLogResponse.builder()
                        .fromStatus(log.getFromStatus())
                        .toStatus(log.getToStatus())
                        .changedByRole(log.getChangedByRole())
                        .note(log.getNote())
                        .createdAt(log.getCreatedAt() != null ? log.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null)
                        .build())
                .toList();

        return BuyerOrderResponse.builder()
                .id(String.valueOf(order.getId()))
                .orderNumber(order.getOrderNumber())
                .buyerId(order.getBuyer() != null ? String.valueOf(order.getBuyer().getId()) : null)
                .sellerId(sellerId)
                .sellerName(sellerName)
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .totalPrice(order.getTotalAmount())
                .itemCount(totalCount)
                .items(itemResponses)
                .logs(logs)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private BuyerOrderItemResponse mapToBuyerOrderItemResponse(OrderItem item) {
        Product product = item.getProduct();
        Card card = product != null ? product.getCard() : null;

        String imageUrl = null;
        if (product != null && product.getCustomImageUrl() != null && !product.getCustomImageUrl().isBlank()) {
            imageUrl = product.getCustomImageUrl();
        } else if (card != null) {
            imageUrl = card.getImageLarge() != null ? card.getImageLarge() : card.getImageSmall();
        }

        BigDecimal unitPrice = item.getPriceAtPurchase() != null ? item.getPriceAtPurchase() : BigDecimal.ZERO;
        int qty = item.getQuantity() != null ? item.getQuantity() : 1;
        BigDecimal itemTotal = unitPrice.multiply(BigDecimal.valueOf(qty));

        String sellerId = item.getSeller() != null ? String.valueOf(item.getSeller().getId()) : null;

        BuyerOrderItemListingDto listingDto = BuyerOrderItemListingDto.builder()
                .id(product != null ? String.valueOf(product.getId()) : null)
                .name(item.getProductNameSnapshot())
                .game("Pokemon")
                .image(imageUrl)
                .price(unitPrice)
                .sellerId(sellerId)
                .set(card != null ? card.getSetName() : null)
                .cardNumber(card != null ? card.getCardNumber() : null)
                .rarity(card != null ? card.getRarity() : null)
                .condition(product != null ? product.getCondition() : null)
                .build();

        return BuyerOrderItemResponse.builder()
                .itemId(String.valueOf(item.getId()))
                .productId(product != null ? String.valueOf(product.getId()) : null)
                .cardId(item.getCardId())
                .productName(item.getProductNameSnapshot())
                .unitPrice(unitPrice)
                .quantity(qty)
                .itemTotal(itemTotal)
                .imageUrl(imageUrl)
                .listing(listingDto)
                .build();
    }

    private String normalizeStatus(String status) {
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "CONFIRMED", "SENT", "FULFILLED", "CANCELLED").contains(value)) {
            throw new OrderStatusValidationException("status",
                "Status must be PENDING, CONFIRMED, SENT, FULFILLED, or CANCELLED");
        }
        return value;
    }
}

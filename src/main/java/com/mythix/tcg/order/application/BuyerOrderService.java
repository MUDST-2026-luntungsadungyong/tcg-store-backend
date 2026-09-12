package com.mythix.tcg.order.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderItemListingDto;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderItemResponse;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderResponse;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BuyerOrderService {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<BuyerOrderResponse> getBuyerOrders(Long buyerId, String status) {
        List<Order> orders;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            orders = orderRepository.findByBuyerIdAndStatusOrderByCreatedAtDesc(buyerId, status.toUpperCase().trim());
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
            orders = orderRepository.findByBuyerIdAndStatusOrderByCreatedAtDesc(buyerId, status.toUpperCase().trim(), pageable);
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

    private BuyerOrderResponse mapToBuyerOrderResponse(Order order) {
        List<OrderItem> items = order.getItems() != null ? order.getItems() : Collections.emptyList();

        int totalCount = items.stream()
                .mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum();

        String sellerId = null;
        String sellerName = null;
        if (!items.isEmpty() && items.get(0).getSeller() != null) {
            sellerId = String.valueOf(items.get(0).getSeller().getId());
            sellerName = items.get(0).getSeller().getFullName();
        }

        List<BuyerOrderItemResponse> itemResponses = items.stream()
                .map(this::mapToBuyerOrderItemResponse)
                .toList();

        String createdAt = order.getCreatedAt() != null ? order.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
        String updatedAt = order.getUpdatedAt() != null ? order.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;

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
}

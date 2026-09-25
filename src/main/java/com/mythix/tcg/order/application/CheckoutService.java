package com.mythix.tcg.order.application;

import com.mythix.tcg.cart.domain.CartItem;
import com.mythix.tcg.cart.persistence.CartItemRepository;
import com.mythix.tcg.order.api.CheckoutDto.CheckoutItemResponse;
import com.mythix.tcg.order.api.CheckoutDto.CheckoutResponse;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CheckoutService {
    private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter RESPONSE_DATE_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final SellerProfileRepository sellerProfileRepository;

    /**
     * The buyer row is locked before reading idempotency or cart state. This serializes
     * checkout attempts for one buyer and makes a retry return the already-created order.
     */
    @Transactional
    public CheckoutResponse checkout(Long buyerId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        User buyer = userRepository.findByIdForUpdate(buyerId)
            .orElseThrow(CheckoutNotAllowedException::new);

        if (normalizedKey != null) {
            var previousOrder = orderRepository.findByIdempotencyKey(normalizedKey);
            if (previousOrder.isPresent()) {
                if (!previousOrder.get().getBuyer().getId().equals(buyerId)) {
                    throw new CheckoutValidationException("Idempotency-Key", "Idempotency-Key is already used");
                }
                return toResponse(previousOrder.get());
            }
        }

        List<CartItem> cartItems = cartItemRepository.findAllByUserIdWithProductForUpdate(buyerId);
        if (cartItems.isEmpty()) {
            throw new EmptyCartException();
        }

        List<Long> productIds = cartItems.stream()
            .map(item -> item.getProduct().getId())
            .sorted()
            .toList();
        List<Product> lockedProducts = productIds.stream()
            .map(productId -> productRepository.findByIdForUpdate(productId).orElse(null))
            .filter(product -> product != null)
            .toList();
        Map<Long, Product> productsById = new HashMap<>();
        lockedProducts.forEach(product -> productsById.put(product.getId(), product));
        if (productsById.size() != productIds.size()) {
            throw new CheckoutListingNotAvailableException(productIds.get(0));
        }

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();
        Map<Long, String> shopNames = new HashMap<>();
        List<Long> sellerIds = cartItems.stream()
            .map(item -> productsById.get(item.getProduct().getId()).getSeller().getId())
            .distinct()
            .toList();
        sellerProfileRepository.findByUserIdIn(sellerIds)
            .forEach(profile -> shopNames.put(profile.getUser().getId(), profile.getShopName()));
        for (CartItem cartItem : cartItems) {
            Product product = productsById.get(cartItem.getProduct().getId());
            validateLine(cartItem, product);

            int quantity = cartItem.getQuantity();
            BigDecimal price = product.getPrice();
            BigDecimal cost = product.getCost() == null ? BigDecimal.ZERO : product.getCost();
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(quantity));
            total = total.add(subtotal);

            orderItems.add(OrderItem.builder()
                .product(product)
                .seller(product.getSeller())
                .cardId(product.getCard().getId())
                .productNameSnapshot(product.getCard().getName())
                .shopNameSnapshot(shopNames.getOrDefault(
                    product.getSeller().getId(),
                    product.getSeller().getFullName() == null ? "" : product.getSeller().getFullName()))
                .priceAtPurchase(price)
                .costAtPurchase(cost)
                .quantity(quantity)
                .build());
        }

        Order order = Order.builder()
            .orderNumber("ORD-TEMP-" + UUID.randomUUID())
            .idempotencyKey(normalizedKey)
            .buyer(buyer)
            .status("PENDING")
            .totalAmount(total)
            .platformFee(BigDecimal.ZERO)
            .sellerNetAmount(total)
            .items(orderItems)
            .build();
        orderItems.forEach(item -> item.setOrder(order));

        Order saved = orderRepository.save(order);
        orderRepository.flush();
        saved.setOrderNumber("ORD-" + LocalDate.now(ZoneOffset.UTC).format(ORDER_DATE_FORMAT) + "-" + saved.getId());
        orderRepository.flush();

        for (CartItem cartItem : cartItems) {
            Product product = productsById.get(cartItem.getProduct().getId());
            try {
                product.setReserved(Math.addExact(product.getReserved() == null ? 0 : product.getReserved(), cartItem.getQuantity()));
            } catch (ArithmeticException exception) {
                throw new CheckoutValidationException("quantity", "quantity is too large");
            }
        }
        cartItemRepository.deleteAllInBatch(cartItems);
        orderRepository.flush();
        return toResponse(saved);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return "checkout-" + UUID.randomUUID();
        }
        String normalized = idempotencyKey.trim();
        if (normalized.isEmpty()) {
            throw new CheckoutValidationException("Idempotency-Key", "Idempotency-Key must not be blank");
        }
        if (normalized.length() > 100) {
            throw new CheckoutValidationException("Idempotency-Key", "Idempotency-Key must not exceed 100 characters");
        }
        return normalized;
    }

    private void validateLine(CartItem cartItem, Product product) {
        Long listingId = product.getId();
        if (!"SHOW".equalsIgnoreCase(product.getStatus())) {
            throw new CheckoutListingNotAvailableException(listingId);
        }
        Integer quantityValue = cartItem.getQuantity();
        if (quantityValue == null || quantityValue <= 0) {
            throw new CheckoutValidationException("quantity", "quantity must be at least 1");
        }
        if (product.getPrice() == null || product.getPrice().signum() < 0) {
            throw new CheckoutValidationException("price", "listing price is invalid");
        }
        int onHand = product.getOnHand() == null ? 0 : product.getOnHand();
        int reserved = product.getReserved() == null ? 0 : product.getReserved();
        int available = Math.max(0, onHand - reserved);
        if (quantityValue > available) {
            throw new CheckoutInsufficientStockException(listingId, available);
        }
    }

    private CheckoutResponse toResponse(Order order) {
        List<CheckoutItemResponse> items = order.getItems().stream()
            .map(item -> {
                BigDecimal price = item.getPriceAtPurchase();
                int quantity = item.getQuantity();
                return new CheckoutItemResponse(
                    String.valueOf(item.getProduct().getId()),
                    String.valueOf(item.getSeller().getId()),
                    item.getProductNameSnapshot(),
                    price,
                    quantity,
                    price.multiply(BigDecimal.valueOf(quantity))
                );
            })
            .toList();
        LocalDateTime createdAt = order.getCreatedAt();
        String createdAtValue = createdAt == null
            ? null
            : createdAt.atOffset(ZoneOffset.UTC).format(RESPONSE_DATE_FORMAT);
        return new CheckoutResponse(
            String.valueOf(order.getId()),
            order.getOrderNumber(),
            order.getStatus(),
            order.getTotalAmount(),
            items,
            createdAtValue
        );
    }
}

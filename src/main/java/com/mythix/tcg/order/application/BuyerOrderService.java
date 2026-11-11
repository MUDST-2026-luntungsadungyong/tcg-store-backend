package com.mythix.tcg.order.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.cart.application.CartService;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderItemListingDto;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderItemResponse;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderResponse;
import com.mythix.tcg.order.api.BuyerOrderDto.OrderVerificationResponse;
import com.mythix.tcg.order.api.BuyerOrderDto.OrderStatusLogResponse;
import com.mythix.tcg.order.api.BuyerOrderDto.ReorderItemResponse;
import com.mythix.tcg.order.api.BuyerOrderDto.ReorderResponse;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.order.domain.OrderStatusLog;
import com.mythix.tcg.order.persistence.OrderStatusLogRepository;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.collection.application.CollectionService;
import com.mythix.tcg.collection.api.CollectionDto.AddToCollectionRequest;
import com.mythix.tcg.voucher.application.VoucherService;
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
    private final VoucherService voucherService;
    private final com.mythix.tcg.auction.application.AuctionSettlementService auctionSettlementService;
    private final CartService cartService;
    private final InvoicePdfService invoicePdfService;

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
    public ReorderResponse reorder(Long buyerId, Long orderId) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<CartService.ReorderItemRequest> requests = order.getItems().stream()
                .map(item -> new CartService.ReorderItemRequest(
                        item.getProduct() == null ? null : item.getProduct().getId(),
                        item.getProductNameSnapshot(),
                        item.getQuantity() == null ? 0 : Math.max(0, item.getQuantity())))
                .filter(item -> item.quantity() > 0)
                .toList();

        CartService.ReorderCartResult result = cartService.addAvailableOrderItems(buyerId, requests);
        List<ReorderItemResponse> itemResponses = result.items().stream()
                .map(item -> new ReorderItemResponse(
                        item.listingId(),
                        item.productName(),
                        item.requestedQuantity(),
                        item.addedQuantity(),
                        item.availableQuantity(),
                        item.outcome()))
                .toList();
        int totalAddedItems = itemResponses.stream().mapToInt(ReorderItemResponse::addedQuantity).sum();
        boolean hasUnavailableItems = itemResponses.stream()
                .anyMatch(item -> item.addedQuantity() < item.requestedQuantity());
        return new ReorderResponse(result.cart(), itemResponses, totalAddedItems, hasUnavailableItems);
    }

    @Transactional(readOnly = true)
    public InvoicePdfService.InvoiceFile generateInvoicePdf(Long buyerId, Long orderId, String verificationUrl) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return invoicePdfService.generate(order, verificationUrl);
    }

    @Transactional(readOnly = true)
    public OrderVerificationResponse verifyOrder(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));
        String updatedAt = order.getUpdatedAt() == null
                ? null
                : order.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return new OrderVerificationResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getOrderType(),
                updatedAt);
    }

    @Transactional
    public BuyerOrderResponse updateOrderStatus(Long buyerId, Long orderId, String newStatus, String note) {
        var auction = auctionSettlementService.lockAuctionForOrder(orderId);
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        String previousStatus = order.getStatus();
        String updatedStatus = normalizeStatus(newStatus);

        if (previousStatus.equalsIgnoreCase(updatedStatus)) {
            return mapToBuyerOrderResponse(order);
        }

        validateStatusTransition(previousStatus, updatedStatus);
        if ("AUCTION".equals(order.getOrderType())
                && !"FULFILLED".equals(updatedStatus) && !"CANCELLED".equals(updatedStatus)) {
            throw new OrderStatusValidationException("status", "Invalid buyer action for an auction order");
        }

        if ("FULFILLED".equalsIgnoreCase(updatedStatus)) {
            transferOwnershipToBuyer(order);
            auction.ifPresent(value -> value.setReceivedAt(java.time.Instant.now()));
        } else if ("CANCELLED".equalsIgnoreCase(updatedStatus)) {
            releaseInventoryOnCancellation(order);
            voucherService.releaseVoucherFromOrder(order);
            auction.ifPresent(value -> auctionSettlementService.closeCancelledOrder(value, false));
        }

        return mapToBuyerOrderResponse(saveStatus(order, updatedStatus, buyerId, "BUYER", note));
    }

    @Transactional
    public void resolveAuctionShipmentAsAdmin(Long adminId, Long orderId, boolean delivered, String reason) {
        var auction = auctionSettlementService.lockAuctionForOrder(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        Order order = orderRepository.findById(orderId)
            .filter(value -> "AUCTION".equals(value.getOrderType()))
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        String outcome = delivered ? "FULFILLED" : "CANCELLED";
        if (outcome.equals(order.getStatus())) {
            return;
        }
        if (!"SENT".equals(order.getStatus())) {
            throw new OrderStatusValidationException("status", "Only a sent auction order can have its shipment resolved");
        }
        if (delivered) {
            transferOwnershipToBuyer(order);
            auction.setReceivedAt(java.time.Instant.now());
        } else {
            // Dispatch already deducted inventory. Resolve the order without assuming the cards were returned.
            auctionSettlementService.closeCancelledOrder(auction, false);
        }
        saveStatus(order, outcome, adminId, "ADMIN", reason.trim());
    }

    private Order saveStatus(Order order, String updatedStatus, Long actorId, String actorRole, String note) {
        String previousStatus = order.getStatus();
        order.setStatus(updatedStatus);
        Order saved = orderRepository.save(order);

        OrderStatusLog log = OrderStatusLog.builder()
                .order(saved)
                .fromStatus(previousStatus)
                .toStatus(updatedStatus)
                .changedBy(userRepository.getReferenceById(actorId))
                .changedByRole(actorRole)
                .note(note)
                .build();
        orderStatusLogRepository.save(log);

        return saved;
    }

    private void validateStatusTransition(String from, String to) {
        if ("FULFILLED".equalsIgnoreCase(from) || "CANCELLED".equalsIgnoreCase(from)) {
            throw new OrderStatusValidationException("status", "Cannot change status of an order that is already " + from);
        }
        if ("CANCELLED".equalsIgnoreCase(to)) {
            if (!List.of("PENDING", "CONFIRMED", "AWAITING_BUYER_CONTACT", "PENDING_SELLER_ACTION").contains(from.toUpperCase(Locale.ROOT))) {
                throw new OrderStatusValidationException("status", "Order can no longer be cancelled");
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
        if ("AUCTION".equalsIgnoreCase(order.getOrderType())) {
            auctionSettlementService.transferAuctionCardToPersonalBinder(order);
            return;
        }
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
        User seller = null;
        if (!items.isEmpty() && items.get(0).getSeller() != null) {
            seller = items.get(0).getSeller();
            sellerId = String.valueOf(seller.getId());
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
                .orderType(order.getOrderType())
                .buyerId(order.getBuyer() != null ? String.valueOf(order.getBuyer().getId()) : null)
                .sellerId(sellerId)
                .sellerName(sellerName)
                .sellerEmail(seller != null ? seller.getEmail() : null)
                .sellerPhone(seller != null ? seller.getPhone() : null)
                .sellerLine(seller != null ? seller.getContactLine() : null)
                .sellerFacebook(seller != null ? seller.getContactFacebook() : null)
                .sellerOtherContact(seller != null ? seller.getContactOther() : null)
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .totalPrice(order.getTotalAmount())
                .subtotalAmount(order.getSubtotalAmount() != null ? order.getSubtotalAmount() : order.getTotalAmount())
                .discountAmount(order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO)
                .voucherCode(order.getVoucherCodeSnapshot())
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
        BigDecimal itemTotal = item.getOrder() != null && "AUCTION".equals(item.getOrder().getOrderType()) ? unitPrice
                : unitPrice.multiply(BigDecimal.valueOf(qty));

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
        if (!List.of("PENDING", "CONFIRMED", "AWAITING_BUYER_CONTACT", "PENDING_SELLER_ACTION", "SENT", "FULFILLED", "CANCELLED").contains(value)) {
            throw new OrderStatusValidationException("status",
                "Status must be PENDING, CONFIRMED, SENT, FULFILLED, or CANCELLED");
        }
        return value;
    }
}

package com.mythix.tcg.cart.application;

import com.mythix.tcg.cart.api.AddCartItemRequest;
import com.mythix.tcg.cart.api.CartItemResponse;
import com.mythix.tcg.cart.api.CartResponse;
import com.mythix.tcg.cart.api.UpdateCartItemRequest;
import com.mythix.tcg.cart.domain.CartItem;
import com.mythix.tcg.cart.persistence.CartItemRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CartService {

    public record ReorderItemRequest(Long listingId, String productName, int quantity) {}

    public record ReorderItemResult(
        String listingId,
        String productName,
        int requestedQuantity,
        int addedQuantity,
        int availableQuantity,
        String outcome
    ) {}

    public record ReorderCartResult(CartResponse cart, List<ReorderItemResult> items) {}

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        requireUser(userId);
        return toCartResponse(cartItemRepository.findAllByUserIdWithProduct(userId));
    }

    @Transactional
    public CartResponse addItem(Long userId, AddCartItemRequest request) {
        User user = requireMutationUser(userId);
        Long listingId = parseListingId(request.listingId());
        Product product = findListing(listingId);
        validatePurchasable(product, listingId);

        CartItem item = cartItemRepository.findByUserIdAndProductIdWithProduct(userId, listingId)
            .orElseGet(() -> CartItem.builder().user(user).product(product).quantity(0).build());
        int requestedQuantity = safeAdd(item.getQuantity(), request.quantity());
        validateStock(product, listingId, requestedQuantity);
        item.setQuantity(requestedQuantity);
        cartItemRepository.save(item);
        return getCart(userId);
    }

    @Transactional
    public CartResponse updateItem(Long userId, String listingIdValue, UpdateCartItemRequest request) {
        requireMutationUser(userId);
        Long listingId = parseListingId(listingIdValue);
        CartItem item = cartItemRepository.findByUserIdAndProductIdWithProduct(userId, listingId)
            .orElseThrow(() -> new CartItemNotFoundException(listingId));
        Product product = item.getProduct();
        validatePurchasable(product, listingId);
        validateStock(product, listingId, request.quantity());
        item.setQuantity(request.quantity());
        return getCart(userId);
    }

    @Transactional
    public void deleteItem(Long userId, String listingIdValue) {
        requireMutationUser(userId);
        Long listingId = parseListingId(listingIdValue);
        CartItem item = cartItemRepository.findByUserIdAndProductIdWithProduct(userId, listingId)
            .orElseThrow(() -> new CartItemNotFoundException(listingId));
        cartItemRepository.delete(item);
    }

    @Transactional
    public ReorderCartResult addAvailableOrderItems(Long userId, List<ReorderItemRequest> requests) {
        User user = requireMutationUser(userId);
        Map<Long, CartItem> cartByProductId = new HashMap<>();
        for (CartItem item : cartItemRepository.findAllByUserIdWithProductForUpdate(userId)) {
            cartByProductId.put(item.getProduct().getId(), item);
        }

        List<ReorderItemResult> results = requests.stream()
            .map(request -> addAvailableOrderItem(user, cartByProductId, request))
            .toList();
        return new ReorderCartResult(getCart(userId), results);
    }

    private ReorderItemResult addAvailableOrderItem(
        User user,
        Map<Long, CartItem> cartByProductId,
        ReorderItemRequest request
    ) {
        if (request.listingId() == null) {
            return unavailableReorderItem(request, 0, "UNAVAILABLE");
        }

        Product product = productRepository.findByIdForUpdate(request.listingId()).orElse(null);
        if (product == null || !"SHOW".equalsIgnoreCase(product.getStatus())) {
            return unavailableReorderItem(request, product == null ? 0 : product.availableStock(), "UNAVAILABLE");
        }

        int availableQuantity = product.availableStock();
        CartItem cartItem = cartByProductId.get(product.getId());
        int currentQuantity = cartItem == null || cartItem.getQuantity() == null ? 0 : cartItem.getQuantity();
        int remainingCapacity = Math.max(0, availableQuantity - currentQuantity);
        int addedQuantity = Math.min(request.quantity(), remainingCapacity);

        if (addedQuantity <= 0) {
            return unavailableReorderItem(request, availableQuantity, "STOCK_LIMIT_REACHED");
        }

        if (cartItem == null) {
            cartItem = CartItem.builder().user(user).product(product).quantity(addedQuantity).build();
            cartByProductId.put(product.getId(), cartItem);
        } else {
            cartItem.setQuantity(currentQuantity + addedQuantity);
        }
        cartItemRepository.save(cartItem);

        String outcome = addedQuantity == request.quantity() ? "ADDED" : "PARTIALLY_ADDED";
        return new ReorderItemResult(
            String.valueOf(product.getId()),
            request.productName(),
            request.quantity(),
            addedQuantity,
            availableQuantity,
            outcome
        );
    }

    private ReorderItemResult unavailableReorderItem(
        ReorderItemRequest request,
        int availableQuantity,
        String outcome
    ) {
        return new ReorderItemResult(
            request.listingId() == null ? null : String.valueOf(request.listingId()),
            request.productName(),
            request.quantity(),
            0,
            availableQuantity,
            outcome
        );
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(CartNotAllowedException::new);
    }

    private User requireMutationUser(Long userId) {
        return userRepository.findByIdForUpdate(userId).orElseThrow(CartNotAllowedException::new);
    }

    private Product findListing(Long listingId) {
        return productRepository.findById(listingId)
            .orElseThrow(() -> new CartListingNotFoundException(listingId));
    }

    private void validatePurchasable(Product product, Long listingId) {
        if (!"SHOW".equalsIgnoreCase(product.getStatus())) {
            throw new CartListingNotAvailableException(listingId);
        }
    }

    private void validateStock(Product product, Long listingId, int requestedQuantity) {
        int availableQuantity = product.availableStock();
        if (requestedQuantity > availableQuantity) {
            throw new InsufficientStockException(listingId, availableQuantity);
        }
    }

    private int safeAdd(Integer current, Integer increment) {
        try {
            return Math.addExact(current == null ? 0 : current, increment);
        } catch (ArithmeticException exception) {
            throw new CartValidationException("quantity", "quantity is too large");
        }
    }

    private Long parseListingId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new CartValidationException("listingId", "listingId must be a positive number");
        }
    }

    private CartResponse toCartResponse(List<CartItem> items) {
        List<CartItemResponse> responses = items.stream().map(this::toItemResponse).toList();
        int totalItems = responses.stream().mapToInt(CartItemResponse::quantity).sum();
        BigDecimal totalAmount = responses.stream()
            .map(CartItemResponse::subtotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(responses, totalItems, totalAmount);
    }

    private CartItemResponse toItemResponse(CartItem item) {
        Product product = item.getProduct();
        var card = product.getCard();
        int availableQuantity = product.availableStock();
        int quantity = item.getQuantity();
        BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
        String image = firstNonBlank(product.getCustomImageUrl(), card.getImageLarge(), card.getImageSmall());
        return new CartItemResponse(
            String.valueOf(product.getId()),
            String.valueOf(product.getSeller().getId()),
            card.getName(),
            card.getSupertype(),
            image == null ? "" : image,
            price,
            product.getCondition(),
            card.getSetName(),
            card.getRarity(),
            quantity,
            "SHOW".equalsIgnoreCase(product.getStatus()) && availableQuantity >= quantity,
            availableQuantity,
            price.multiply(BigDecimal.valueOf(quantity))
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

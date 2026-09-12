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
import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService {

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
        User user = requireUser(userId);
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
        requireUser(userId);
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
        requireUser(userId);
        Long listingId = parseListingId(listingIdValue);
        CartItem item = cartItemRepository.findByUserIdAndProductIdWithProduct(userId, listingId)
            .orElseThrow(() -> new CartItemNotFoundException(listingId));
        cartItemRepository.delete(item);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(CartNotAllowedException::new);
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
        int availableQuantity = availableQuantity(product);
        if (requestedQuantity > availableQuantity) {
            throw new InsufficientStockException(listingId, availableQuantity);
        }
    }

    private int availableQuantity(Product product) {
        int onHand = product.getOnHand() == null ? 0 : product.getOnHand();
        int reserved = product.getReserved() == null ? 0 : product.getReserved();
        return Math.max(0, onHand - reserved);
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
        int availableQuantity = availableQuantity(product);
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

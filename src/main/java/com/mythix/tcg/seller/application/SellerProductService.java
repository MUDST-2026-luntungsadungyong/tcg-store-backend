package com.mythix.tcg.seller.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.seller.api.SellerProductRequest;
import com.mythix.tcg.seller.api.SellerProductResponse;
import com.mythix.tcg.seller.persistence.SellerOrderItemRepository;
import com.mythix.tcg.seller.persistence.SellerProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SellerProductService {

    private static final String DELETED = "DELETED";
    private static final String SELLER_CARD_PREFIX = "seller-listing-";

    private final SellerProductRepository productRepository;
    private final SellerOrderItemRepository orderItemRepository;
    private final CardRepository cardRepository;
    private final UserRepository userRepository;
    private final SellerProductImageStorage imageStorage;

    @Transactional(readOnly = true)
    public List<SellerProductResponse> findAll(
        Long sellerId,
        String search,
        String game,
        String status,
        Integer page,
        Integer size
    ) {
        requireSeller(sellerId);
        validateListParameters(status, page, size);

        List<Product> products = productRepository
            .findAllBySeller_IdAndStatusNotOrderByUpdatedAtDescIdDesc(sellerId, DELETED);
        Map<Long, Long> activeOrders = activeOrderCounts(products);

        Stream<SellerProductResponse> results = products.stream()
            .map(product -> toResponse(product, activeOrders.getOrDefault(product.getId(), 0L)))
            .filter(product -> matchesSearch(product, search))
            .filter(product -> matchesGame(product, game))
            .filter(product -> status == null || status.isBlank() || product.status().equals(status));

        if (page != null && size != null) {
            results = results.skip((long) page * size).limit(size);
        }
        return results.toList();
    }

    @Transactional
    public SellerProductResponse create(Long sellerId, SellerProductRequest request, MultipartFile imageFile) {
        User seller = requireSeller(sellerId);
        String imageUrl = resolveImage(request.image(), imageFile);

        Card card = cardRepository.save(Card.builder()
            .id(SELLER_CARD_PREFIX + UUID.randomUUID())
            .name(request.name().trim())
            .supertype(canonicalGame(request.game()))
            .setName(trimToNull(request.set()))
            .rarity(trimToNull(request.rarity()))
            .marketPrice(request.price())
            .build());

        Product product = productRepository.save(Product.builder()
            .seller(seller)
            .card(card)
            .customImageUrl(imageUrl)
            .condition(conditionOrDefault(request.condition()))
            .language("EN")
            .price(request.price())
            .cost(request.cost())
            .onHand(request.stock())
            .reserved(0)
            .status(request.status())
            .build());

        return toResponse(product, 0L);
    }

    @Transactional
    public SellerProductResponse update(
        Long sellerId,
        Long productId,
        SellerProductRequest request,
        MultipartFile imageFile
    ) {
        requireSeller(sellerId);
        Product product = findOwnedForUpdate(sellerId, productId);
        String previousImage = product.getCustomImageUrl();
        String imageUrl = resolveImage(request.image(), imageFile);

        Card card = editableCard(product);
        card.setName(request.name().trim());
        card.setSupertype(canonicalGame(request.game()));
        card.setSetName(trimToNull(request.set()));
        card.setRarity(trimToNull(request.rarity()));
        card.setMarketPrice(request.price());

        product.setCustomImageUrl(imageUrl);
        product.setCondition(conditionOrDefault(request.condition()));
        product.setPrice(request.price());
        product.setCost(request.cost());
        product.setOnHand(addAvailableAndReserved(request.stock(), product.getReserved()));
        product.setStatus(request.status());

        if (previousImage != null && !previousImage.equals(imageUrl)) {
            deleteImageIfUnreferenced(previousImage, productId);
        }

        long activeOrders = orderItemRepository.countActiveOrders(productId);
        return toResponse(product, activeOrders);
    }

    @Transactional
    public void changeStatus(Long sellerId, Long productId, String status) {
        requireSeller(sellerId);
        Product product = findOwnedForUpdate(sellerId, productId);
        product.setStatus(status);
    }

    @Transactional
    public void delete(Long sellerId, Long productId) {
        requireSeller(sellerId);
        Product product = findOwnedForUpdate(sellerId, productId);
        if (!"HIDE".equals(product.getStatus())) {
            throw new SellerProductMustBeHiddenException();
        }

        long activeOrders = orderItemRepository.countActiveOrders(productId);
        if (activeOrders > 0 || product.getReserved() > 0) {
            throw new SellerProductHasActiveOrdersException(Math.max(activeOrders, 1L));
        }

        if (orderItemRepository.existsByProduct_Id(productId)) {
            product.setStatus(DELETED);
            return;
        }

        deleteImageIfUnreferenced(product.getCustomImageUrl(), productId);
        productRepository.delete(product);
        productRepository.flush();
    }

    private User requireSeller(Long sellerId) {
        User seller = userRepository.findById(sellerId).orElseThrow(SellerRequiredException::new);
        boolean approvedSeller = "APPROVED".equalsIgnoreCase(seller.getSellerStatus());
        if (!approvedSeller) {
            throw new SellerRequiredException();
        }
        return seller;
    }

    private Product findOwnedForUpdate(Long sellerId, Long productId) {
        Product product = productRepository.findActiveByIdForUpdate(productId)
            .orElseThrow(() -> new SellerProductNotFoundException(productId));
        if (!product.getSeller().getId().equals(sellerId)) {
            throw new SellerProductForbiddenException();
        }
        return product;
    }

    private Card editableCard(Product product) {
        Card existing = product.getCard();
        if (existing.getId().startsWith(SELLER_CARD_PREFIX)) {
            return existing;
        }

        Card dedicated = cardRepository.save(Card.builder()
            .id(SELLER_CARD_PREFIX + UUID.randomUUID())
            .name(existing.getName())
            .supertype(existing.getSupertype())
            .setName(existing.getSetName())
            .rarity(existing.getRarity())
            .imageSmall(existing.getImageSmall())
            .imageLarge(existing.getImageLarge())
            .marketPrice(existing.getMarketPrice() == null ? BigDecimal.ZERO : existing.getMarketPrice())
            .build());
        product.setCard(dedicated);
        return dedicated;
    }

    private String resolveImage(String requestedImageUrl, MultipartFile imageFile) {
        if (imageFile != null && !imageFile.isEmpty()) {
            return imageStorage.store(imageFile);
        }

        String imageUrl = trimToNull(requestedImageUrl);
        if (imageUrl == null) {
            throw new SellerProductValidationException("image", "Product image or image URL is required");
        }
        validateImageUrl(imageUrl);
        return imageUrl;
    }

    private void validateImageUrl(String imageUrl) {
        if (imageUrl.startsWith("/uploads/products/")) {
            return;
        }
        try {
            URI uri = URI.create(imageUrl);
            boolean validScheme = "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());
            if (!validScheme || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new SellerProductValidationException("image", "Image URL must use http or https");
        }
    }

    private int addAvailableAndReserved(int available, Integer reservedValue) {
        int reserved = reservedValue == null ? 0 : reservedValue;
        try {
            return Math.addExact(available, reserved);
        } catch (ArithmeticException exception) {
            throw new SellerProductValidationException("stock", "Stock is too large");
        }
    }

    private void deleteImageIfUnreferenced(String imageUrl, Long currentProductId) {
        if (imageUrl != null
            && !productRepository.existsByCustomImageUrlAndIdNot(imageUrl, currentProductId)) {
            imageStorage.delete(imageUrl);
        }
    }

    private Map<Long, Long> activeOrderCounts(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (SellerOrderItemRepository.ActiveOrderCount row
            : orderItemRepository.countActiveOrdersByProductIds(ids)) {
            counts.put(row.getProductId(), row.getActiveOrderCount());
        }
        return counts;
    }

    private SellerProductResponse toResponse(Product product, long activeOrders) {
        Card card = product.getCard();
        String image = firstNonBlank(product.getCustomImageUrl(), card.getImageLarge(), card.getImageSmall());
        int reserved = product.getReserved() == null ? 0 : product.getReserved();
        int onHand = product.getOnHand() == null ? 0 : product.getOnHand();
        return new SellerProductResponse(
            String.valueOf(product.getId()),
            String.valueOf(product.getSeller().getId()),
            card.getName(),
            canonicalGame(card.getSupertype()),
            image == null ? "" : image,
            product.getPrice(),
            product.getCost() == null ? BigDecimal.ZERO : product.getCost(),
            Math.max(0, onHand - reserved),
            product.getStatus(),
            product.getCondition(),
            card.getSetName(),
            card.getRarity(),
            activeOrders,
            activeOrders
        );
    }

    private boolean matchesSearch(SellerProductResponse product, String search) {
        String query = trimToNull(search);
        if (query == null) {
            return true;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return Stream.of(
                product.id(), product.name(), product.game(), product.image(), product.status(),
                product.condition(), product.set(), product.rarity(), product.price(), product.cost(), product.stock()
            )
            .filter(value -> value != null)
            .map(String::valueOf)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains(normalized));
    }

    private boolean matchesGame(SellerProductResponse product, String game) {
        String requested = trimToNull(game);
        return requested == null || product.game().equalsIgnoreCase(canonicalGame(requested));
    }

    private void validateListParameters(String status, Integer page, Integer size) {
        if (status != null && !status.isBlank() && !"SHOW".equals(status) && !"HIDE".equals(status)) {
            throw new SellerProductValidationException("status", "Status must be SHOW or HIDE");
        }
        if ((page == null) != (size == null)) {
            throw new SellerProductValidationException("page", "Page and size must be provided together");
        }
        if (page != null && page < 0) {
            throw new SellerProductValidationException("page", "Page must be 0 or more");
        }
        if (size != null && (size < 1 || size > 100)) {
            throw new SellerProductValidationException("size", "Size must be between 1 and 100");
        }
    }

    private String canonicalGame(String game) {
        String value = game == null ? "" : game.trim();
        if (value.equalsIgnoreCase("Pokemon") || value.equalsIgnoreCase("Pokémon")) {
            return "Pokemon";
        }
        if (value.equalsIgnoreCase("YGO") || value.equalsIgnoreCase("Yu-Gi-Oh")
            || value.equalsIgnoreCase("Yu-Gi-Oh!")) {
            return "YGO";
        }
        if (value.equalsIgnoreCase("MTG") || value.equalsIgnoreCase("Magic: The Gathering")) {
            return "MTG";
        }
        if (value.equalsIgnoreCase("One Piece")) {
            return "One Piece";
        }
        return value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String conditionOrDefault(String condition) {
        String value = trimToNull(condition);
        return value == null ? "Unspecified" : value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

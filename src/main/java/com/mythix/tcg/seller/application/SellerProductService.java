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

    /* The current repository still exposes a legacy status-exclusion method.
       This value is intentionally outside the Revision 5 status set so all
       valid listings are returned without ever persisting a legacy status. */
    private static final String NO_REVISION_5_STATUS = "__NO_REVISION_5_STATUS__";
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
            .findAllBySeller_IdAndStatusNotOrderByUpdatedAtDescIdDesc(sellerId, NO_REVISION_5_STATUS);
        Map<Long, Long> activeOrders = activeOrderCounts(products);

        Stream<SellerProductResponse> results = products.stream()
            .map(product -> toResponse(product, activeOrders.getOrDefault(product.getId(), 0L)))
            .filter(product -> matchesSearch(product, search))
            .filter(product -> matchesGame(product, game))
            .filter(product -> status == null || status.isBlank()
                || product.status().equalsIgnoreCase(status.trim()));

        if (page != null && size != null) {
            results = results.skip((long) page * size).limit(size);
        }
        return results.toList();
    }

    @Transactional
    public SellerProductResponse create(Long sellerId, SellerProductRequest request, MultipartFile imageFile) {
        User seller = requireSeller(sellerId);
        String imageUrl = resolveImage(request.image(), imageFile);

        String requestedCardId = trimToNull(request.cardId());
        Card card;
        if (requestedCardId != null) {
            card = cardRepository.findById(requestedCardId)
                .filter(foundCard -> Boolean.TRUE.equals(foundCard.getActive()))
                .orElseThrow(() -> new SellerProductValidationException("cardId", "Card was not found in the database"));
        } else {
            String cardId = SELLER_CARD_PREFIX + UUID.randomUUID();
            card = cardRepository.save(Card.builder()
                .id(cardId)
                .name(request.name().trim())
                .supertype(canonicalGame(request.game()))
                .setName(defaultSet(request.set()))
                .cardNumber(shortCardNumber(cardId))
                .rarity(trimToNull(request.rarity()))
                .imageSmall(imageUrl)
                .imageLarge(imageUrl)
                .marketPrice(request.price())
                .build());
        }

        Product product = productRepository.save(Product.builder()
            .seller(seller)
            .card(card)
            .customImageUrl(imageUrl)
            .condition(normalizeCondition(request.condition()))
            .language(normalizeLanguage(request.language()))
            .price(request.price())
            .cost(request.cost())
            .onHand(request.stock())
            .reserved(0)
            .status(normalizeStatus(request.status()))
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

        product.setCustomImageUrl(imageUrl);
        product.setCondition(normalizeCondition(request.condition()));
        product.setLanguage(normalizeLanguage(request.language()));
        product.setPrice(request.price());
        product.setCost(request.cost());
        product.setOnHand(addAvailableAndReserved(request.stock(), product.getReserved()));
        product.setStatus(normalizeStatus(request.status()));

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
        product.setStatus(normalizeStatus(status));
    }

    @Transactional
    public void delete(Long sellerId, Long productId) {
        requireSeller(sellerId);
        Product product = findOwnedForUpdate(sellerId, productId);

        long activeOrders = orderItemRepository.countActiveOrders(productId);
        if (activeOrders > 0 || product.getReserved() > 0) {
            throw new SellerProductHasActiveOrdersException(Math.max(activeOrders, 1L));
        }

        if (orderItemRepository.existsByProduct_Id(productId)) {
            // Revision 5 keeps order history through a restrictive FK and has
            // no DELETED status. Retain the listing as hidden when history exists.
            product.setStatus("HIDE");
            return;
        }

        deleteImageIfUnreferenced(product.getCustomImageUrl(), productId);
        productRepository.delete(product);
        productRepository.flush();
    }

    private User requireSeller(Long sellerId) {
        User seller = userRepository.findById(sellerId).orElseThrow(SellerRequiredException::new);
        if (!"APPROVED".equalsIgnoreCase(seller.getSellerStatus())) {
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
            product.getLanguage(),
            card.getSetName(),
            card.getRarity(),
            card.getId(),
            card.getCardNumber(),
            card.getSupertype(),
            card.getSubtypes(),
            card.getHp(),
            card.getTypes(),
            card.getArtist(),
            card.getImageSmall(),
            card.getImageLarge(),
            card.getMarketPrice(),
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
        if (status != null && !status.isBlank()) {
            normalizeStatus(status);
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

    private String normalizeCondition(String condition) {
        String value = trimToNull(condition);
        if (value == null) {
            return "NM";
        }
        return switch (value.toUpperCase(Locale.ROOT).replace('-', ' ').replace('_', ' ')) {
            case "NM", "NEAR MINT" -> "NM";
            case "LP", "LIGHTLY PLAYED" -> "LP";
            case "MP", "MODERATELY PLAYED" -> "MP";
            case "HP", "HEAVILY PLAYED" -> "HP";
            case "DMG", "DAMAGED" -> "DMG";
            default -> throw new SellerProductValidationException("condition", "Condition must be NM, LP, MP, HP, or DMG");
        };
    }

    private String normalizeLanguage(String language) {
        String value = trimToNull(language);
        if (value == null) {
            return "EN";
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!List.of("EN", "JP", "TH").contains(normalized)) {
            throw new SellerProductValidationException("language", "Language must be EN, JP, or TH");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        String value = trimToNull(status);
        if (value == null) {
            throw new SellerProductValidationException("status", "Status must be SHOW, HIDE, or OUT_OF_STOCK");
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!List.of("SHOW", "HIDE", "OUT_OF_STOCK").contains(normalized)) {
            throw new SellerProductValidationException("status", "Status must be SHOW, HIDE, or OUT_OF_STOCK");
        }
        return normalized;
    }

    private String defaultSet(String set) {
        return firstNonBlank(set, "Seller Listings");
    }

    private String shortCardNumber(String value) {
        String normalized = firstNonBlank(value, "SELLER");
        return normalized.length() <= 50 ? normalized : normalized.substring(0, 50);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

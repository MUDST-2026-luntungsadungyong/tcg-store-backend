package com.mythix.tcg.seller.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.seller.api.SellerProductRequest;
import com.mythix.tcg.seller.api.SellerProductResponse;
import com.mythix.tcg.seller.api.ListingRulesRequest;
import com.mythix.tcg.seller.api.ListingRulesResponse;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
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
    private final SellerProductImageStore imageStorage;

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
        return create(sellerId, request, imageFile, null, null, null);
    }

    @Transactional
    public SellerProductResponse create(Long sellerId, SellerProductRequest request, MultipartFile imageFile,
        MultipartFile frontImage, MultipartFile backImage, List<MultipartFile> additionalImages) {
        User seller = requireSeller(sellerId);
        validateGrading(request);
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

        Product product = Product.builder()
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
            .build();
        applyActualPhotos(product, request, frontImage, backImage, additionalImages);
        applyGrading(product, request);
        if (product.getActualImageFrontUrl() != null) product.setCustomImageUrl(product.getActualImageFrontUrl());
        enforcePublishedPolicy(product);
        productRepository.save(product);

        return toResponse(product, 0L);
    }

    @Transactional
    public SellerProductResponse update(
        Long sellerId,
        Long productId,
        SellerProductRequest request,
        MultipartFile imageFile
    ) {
        return update(sellerId, productId, request, imageFile, null, null, null);
    }

    @Transactional
    public SellerProductResponse update(Long sellerId, Long productId, SellerProductRequest request,
        MultipartFile imageFile, MultipartFile frontImage, MultipartFile backImage,
        List<MultipartFile> additionalImages) {
        requireSeller(sellerId);
        validateGrading(request);
        Product product = findOwnedForUpdate(sellerId, productId);
        Set<String> previousImages = imageReferences(product);
        Set<String> previousActualImages = actualImageReferences(product);
        String imageUrl = resolveImage(request.image(), imageFile);

        product.setCustomImageUrl(imageUrl);
        product.setCondition(normalizeCondition(request.condition()));
        product.setLanguage(normalizeLanguage(request.language()));
        product.setPrice(request.price());
        product.setCost(request.cost());
        product.setOnHand(addAvailableAndReserved(request.stock(), product.getReserved()));
        product.setStatus(normalizeStatus(request.status()));

        applyActualPhotos(product, request, frontImage, backImage, additionalImages);
        applyGrading(product, request);
        if (product.getActualImageFrontUrl() != null) {
            product.setCustomImageUrl(product.getActualImageFrontUrl());
        } else if (previousActualImages.contains(product.getCustomImageUrl())
            && !actualImageReferences(product).contains(product.getCustomImageUrl())) {
            String catalogImage = firstNonBlank(product.getCard().getImageLarge(), product.getCard().getImageSmall());
            product.setCustomImageUrl(catalogImage == null ? "" : catalogImage);
        }
        enforcePublishedPolicy(product);
        Set<String> retainedImages = imageReferences(product);
        previousImages.stream().filter(url -> !retainedImages.contains(url))
            .forEach(url -> deleteImageIfUnreferenced(url, productId));

        long activeOrders = orderItemRepository.countActiveOrders(productId);
        return toResponse(product, activeOrders);
    }

    @Transactional
    public void changeStatus(Long sellerId, Long productId, String status) {
        requireSeller(sellerId);
        Product product = findOwnedForUpdate(sellerId, productId);
        product.setStatus(normalizeStatus(status));
        enforcePublishedPolicy(product);
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

        imageReferences(product).forEach(url -> deleteImageIfUnreferenced(url, productId));
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

    @Transactional(readOnly = true)
    public ListingRulesResponse validateListingRules(Long sellerId, ListingRulesRequest request) {
        requireSeller(sellerId);
        Card card = cardRepository.findById(request.cardId())
            .filter(found -> Boolean.TRUE.equals(found.getActive()))
            .orElseThrow(() -> new SellerProductValidationException("cardId", "Card was not found in the database"));
        return ListingImagePolicy.evaluate(card.getSupertype(), policyRarity(card), request.price(),
            normalizeCondition(request.condition()), normalizeGradingCompany(request.gradingCompany()));
    }

    private String policyRarity(Card card) {
        // Seller-created legacy catalog rows are not an authoritative rarity source.
        return card.getId().startsWith(SELLER_CARD_PREFIX) ? null : card.getRarity();
    }

    private void enforcePublishedPolicy(Product product) {
        if (!"SHOW".equals(product.getStatus())) return;
        ListingRulesResponse policy = ListingImagePolicy.evaluate(product.getCard().getSupertype(),
            policyRarity(product.getCard()), product.getPrice(), product.getCondition(), product.getGradingCompany());
        if (policy.imageMandatory()
            && (product.getActualImageFrontUrl() == null || product.getActualImageBackUrl() == null)) {
            throw new SellerProductValidationException("actualImages",
                "Upload actual front and back photos before publishing this listing: "
                    + String.join(", ", policy.reasons()));
        }
    }

    private void applyActualPhotos(Product product, SellerProductRequest request,
        MultipartFile frontImage, MultipartFile backImage, List<MultipartFile> additionalImages) {
        String front = retainSide(request.actualImageFrontUrl(), product.getActualImageFrontUrl(), "actualImageFrontUrl");
        String back = retainSide(request.actualImageBackUrl(), product.getActualImageBackUrl(), "actualImageBackUrl");
        List<String> oldExtras = product.getAdditionalImageUrls();
        List<String> retained = request.additionalImageUrls() == null
            ? new ArrayList<>(oldExtras) : new ArrayList<>(request.additionalImageUrls());
        if (retained.stream().anyMatch(url -> url == null || !oldExtras.contains(url))
            || new LinkedHashSet<>(retained).size() != retained.size()) {
            throw new SellerProductValidationException("additionalImageUrls",
                "Only existing detail photos belonging to this listing can be retained");
        }
        List<MultipartFile> uploads = additionalImages == null ? List.of() : additionalImages;
        if (retained.size() + uploads.size() > ListingImagePolicy.MAX_ADDITIONAL_IMAGES) {
            throw new SellerProductValidationException("additionalImages", "At most four detail photos are allowed");
        }
        SellerProductImageValidator validator = new SellerProductImageValidator();
        if (frontImage != null) validator.validate(frontImage);
        if (backImage != null) validator.validate(backImage);
        uploads.forEach(validator::validate);
        if (frontImage != null) front = imageStorage.store(frontImage);
        if (backImage != null) back = imageStorage.store(backImage);
        for (MultipartFile file : uploads) retained.add(imageStorage.store(file));
        product.setActualImageFrontUrl(front);
        product.setActualImageBackUrl(back);
        // Mutate Hibernate's managed collection so removals and ordering flush correctly.
        product.getAdditionalImageUrls().clear();
        product.getAdditionalImageUrls().addAll(retained);
    }

    private String retainSide(String requested, String current, String field) {
        if (requested == null) return current;
        String normalized = trimToNull(requested);
        if (normalized == null) return null;
        if (!normalized.equals(current)) {
            throw new SellerProductValidationException(field,
                "Upload a new photo or retain the existing photo for this side of this listing");
        }
        return current;
    }

    private String normalizeGradingCompany(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!List.of("PSA", "BGS", "CGC").contains(normalized)) {
            throw new SellerProductValidationException("gradingCompany", "Grading company must be PSA, BGS, or CGC");
        }
        return normalized;
    }

    private void validateGrading(SellerProductRequest request) {
        String company = normalizeGradingCompany(request.gradingCompany());
        String grade = trimToNull(request.grade());
        String cert = trimToNull(request.certNumber());
        if ((company == null && (grade != null || cert != null))
            || (company != null && (grade == null || cert == null))) {
            throw new SellerProductValidationException("gradingCompany",
                "Provide grading company, grade and certificate number together, or leave all three empty");
        }
    }

    private void applyGrading(Product product, SellerProductRequest request) {
        product.setGradingCompany(normalizeGradingCompany(request.gradingCompany()));
        product.setGrade(trimToNull(request.grade()));
        product.setCertNumber(trimToNull(request.certNumber()));
    }

    private Set<String> imageReferences(Product product) {
        Set<String> images = actualImageReferences(product);
        images.add(product.getCustomImageUrl());
        images.remove(null);
        return images;
    }

    private Set<String> actualImageReferences(Product product) {
        Set<String> images = new LinkedHashSet<>();
        images.add(product.getActualImageFrontUrl());
        images.add(product.getActualImageBackUrl());
        images.addAll(product.getAdditionalImageUrls());
        images.remove(null);
        return images;
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
            && !productRepository.imageReferencedByAnotherProduct(imageUrl, currentProductId)) {
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
            activeOrders,
            product.getActualImageFrontUrl(),
            product.getActualImageBackUrl(),
            List.copyOf(product.getAdditionalImageUrls()),
            product.getGradingCompany(),
            product.getGrade(),
            product.getCertNumber()
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
        if (value.equalsIgnoreCase("Pokemon") || value.equalsIgnoreCase("Pokémon")
            || value.equalsIgnoreCase("Trainer") || value.equalsIgnoreCase("Energy")) {
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

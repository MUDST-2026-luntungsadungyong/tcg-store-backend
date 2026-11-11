package com.mythix.tcg.product.application;

import com.mythix.tcg.catalog.application.CardFuzzySearchService;
import com.mythix.tcg.catalog.domain.FuzzySearchResult;
import com.mythix.tcg.catalog.persistence.CardNameSearch;
import com.mythix.tcg.product.api.ProductPageResponse;
import com.mythix.tcg.product.api.ProductResponse;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final SellerProfileRepository sellerProfileRepository;

    @Autowired(required = false)
    private CardFuzzySearchService fuzzySearchService;

    @Transactional(readOnly = true)
    public ProductPageResponse browseProducts(String search, String rarity, String stage, String condition,
                                             String language, String cardId, Long sellerId, BigDecimal minPrice,
                                             BigDecimal maxPrice, Pageable pageable) {
        return browseProducts(search, rarity, stage, condition, language, cardId, sellerId, minPrice, maxPrice, false, pageable);
    }

    @Transactional(readOnly = true)
    public ProductPageResponse browseProducts(String search, String rarity, String stage, String condition,
                                             String language, String cardId, Long sellerId, BigDecimal minPrice,
                                             BigDecimal maxPrice, boolean fuzzy, Pageable pageable) {
        String rawSearch = (search != null) ? search.trim() : "";
        String term = CardNameSearch.term(rawSearch);

        Specification<Product> spec = createBrowseSpec(term, rarity, stage, condition, language, cardId, sellerId, minPrice, maxPrice);
        Page<Product> page = productRepository.findAll(spec, pageable);

        String didYouMean = null;
        boolean fuzzyMatched = false;

        // If no results or fuzzy explicitly requested, try typo suggestion
        if ((page.isEmpty() || fuzzy) && !rawSearch.isEmpty() && fuzzySearchService != null) {
            Optional<FuzzySearchResult> suggestionOpt = fuzzySearchService.findBestSuggestion(rawSearch);
            if (suggestionOpt.isPresent()) {
                FuzzySearchResult suggestion = suggestionOpt.get();
                String suggestedTerm = CardNameSearch.term(suggestion.getSuggestedQuery());
                Specification<Product> fuzzySpec = createBrowseSpec(
                        suggestedTerm, rarity, stage, condition, language, cardId, sellerId, minPrice, maxPrice);
                Page<Product> fuzzyPage = productRepository.findAll(fuzzySpec, pageable);
                if (!fuzzyPage.isEmpty() || page.isEmpty()) {
                    page = fuzzyPage;
                    didYouMean = suggestion.getSuggestedQuery();
                    fuzzyMatched = true;
                }
            }
        }

        List<Long> sellerIds = page.getContent().stream()
                .map(Product::getSeller)
                .filter(seller -> seller != null && seller.getId() != null)
                .map(seller -> seller.getId())
                .distinct()
                .toList();
        Map<Long, String> shopNames = sellerProfileRepository.findByUserIdIn(sellerIds).stream()
                .collect(Collectors.toMap(profile -> profile.getUser().getId(), profile -> profile.getShopName()));
        Page<ProductResponse> dtoPage = page.map(product -> ProductResponse.fromEntity(
                product,
                product.getSeller() == null ? null : shopNames.get(product.getSeller().getId())));

        return ProductPageResponse.fromPage(dtoPage, didYouMean, rawSearch.isEmpty() ? null : rawSearch, fuzzyMatched);
    }

    private Specification<Product> createBrowseSpec(String term, String rarity, String stage, String condition,
                                                    String language, String cardId, Long sellerId, BigDecimal minPrice,
                                                    BigDecimal maxPrice) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always enforce status = 'SHOW' for public browsing
            predicates.add(cb.equal(root.get("status"), "SHOW"));

            if (term != null && !term.isEmpty()) {
                predicates.add(CardNameSearch.matches(root.get("card"), query, cb, term));
            }

            // Filter by rarity (case-insensitive)
            if (rarity != null && !rarity.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("card").get("rarity")), rarity.trim().toLowerCase()));
            }

            // Filter by stage / subtypes (case-insensitive contains)
            if (stage != null && !stage.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("card").get("subtypes")), "%" + stage.trim().toLowerCase() + "%"));
            }

            // Filter by condition (case-insensitive)
            if (condition != null && !condition.isBlank()) {
                predicates.add(cb.equal(root.get("condition"), normalizeCondition(condition)));
            }

            if (language != null && !language.isBlank()) {
                predicates.add(cb.equal(root.get("language"), normalizeLanguage(language)));
            }

            if (cardId != null && !cardId.isBlank()) {
                predicates.add(cb.equal(root.get("card").get("id"), cardId.trim()));
            }

            if (sellerId != null) {
                predicates.add(cb.equal(root.get("seller").get("id"), sellerId));
            }

            // Filter by price range
            if (minPrice != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), minPrice));
            }
            if (maxPrice != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), maxPrice));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findByIdAndStatus(id, "SHOW")
                .orElseThrow(() -> new ProductNotFoundException("Product with ID " + id + " not found or is not publicly visible"));
        return toResponse(product);
    }

    private ProductResponse toResponse(Product product) {
        String shopName = product.getSeller() == null || product.getSeller().getId() == null
                ? null
                : sellerProfileRepository.findByUserId(product.getSeller().getId())
                    .map(profile -> profile.getShopName())
                    .orElse(null);
        return ProductResponse.fromEntity(product, shopName);
    }

    private String normalizeCondition(String condition) {
        String clean = condition.trim().toUpperCase();
        return switch (clean) {
            case "NEAR MINT" -> "NM";
            case "LIGHTLY PLAYED" -> "LP";
            case "MODERATELY PLAYED" -> "MP";
            case "HEAVILY PLAYED" -> "HP";
            case "DAMAGED" -> "DMG";
            default -> clean;
        };
    }

    private String normalizeLanguage(String language) {
        String clean = language.trim().toUpperCase();
        return switch (clean) {
            case "THAI" -> "TH";
            case "ENGLISH" -> "EN";
            case "JAPANESE" -> "JP";
            default -> clean;
        };
    }
}

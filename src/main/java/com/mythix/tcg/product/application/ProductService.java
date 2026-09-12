package com.mythix.tcg.product.application;

import com.mythix.tcg.product.api.ProductPageResponse;
import com.mythix.tcg.product.api.ProductResponse;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public ProductPageResponse browseProducts(String search, String rarity, String stage, String condition,
                                             BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always enforce status = 'SHOW' for public browsing
            predicates.add(cb.equal(root.get("status"), "SHOW"));

            // Search by card name (case-insensitive)
            if (search != null && !search.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("card").get("name")), "%" + search.trim().toLowerCase() + "%"));
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
                predicates.add(cb.equal(cb.lower(root.get("condition")), condition.trim().toLowerCase()));
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

        Page<Product> page = productRepository.findAll(spec, pageable);
        Page<ProductResponse> dtoPage = page.map(ProductResponse::fromEntity);
        return ProductPageResponse.fromPage(dtoPage);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findByIdAndStatus(id, "SHOW")
                .orElseThrow(() -> new ProductNotFoundException("Product with ID " + id + " not found or is not publicly visible"));
        return ProductResponse.fromEntity(product);
    }
}

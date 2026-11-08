package com.mythix.tcg.seller.persistence;

import com.mythix.tcg.product.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SellerProductRepository extends JpaRepository<Product, Long> {

    @EntityGraph(attributePaths = "card")
    List<Product> findAllBySeller_IdAndStatusNotOrderByUpdatedAtDescIdDesc(Long sellerId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :productId")
    Optional<Product> findActiveByIdForUpdate(@Param("productId") Long productId);

    boolean existsByCustomImageUrlAndIdNot(String customImageUrl, Long productId);

    @Query("select count(distinct p.id) > 0 from Product p left join p.additionalImageUrls extra "
        + "where p.id <> :productId and (p.customImageUrl = :url or p.actualImageFrontUrl = :url "
        + "or p.actualImageBackUrl = :url or extra = :url)")
    boolean imageReferencedByAnotherProduct(@Param("url") String url, @Param("productId") Long productId);
}

package com.mythix.tcg.product.persistence;

import com.mythix.tcg.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByIdAndStatus(Long id, String status);

    List<Product> findBySellerId(Long sellerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p join fetch p.card join fetch p.seller where p.id in :ids order by p.id")
    List<Product> findAllByIdInForUpdate(@Param("ids") List<Long> ids);
}

package com.mythix.tcg.cart.persistence;

import com.mythix.tcg.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    @Query("""
        select item
        from CartItem item
        join fetch item.product product
        join fetch product.card
        where item.user.id = :userId
        order by item.id
        """)
    List<CartItem> findAllByUserIdWithProduct(@Param("userId") Long userId);

    @Query("""
        select item
        from CartItem item
        join fetch item.product product
        join fetch product.card
        where item.user.id = :userId and item.product.id = :productId
        """)
    Optional<CartItem> findByUserIdAndProductIdWithProduct(
        @Param("userId") Long userId,
        @Param("productId") Long productId
    );
}

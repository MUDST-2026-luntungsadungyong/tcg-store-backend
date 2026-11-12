package com.mythix.tcg.seller.persistence;

import com.mythix.tcg.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface SellerOrderItemRepository extends JpaRepository<OrderItem, Long> {

    interface ActiveOrderCount {
        Long getProductId();
        Long getActiveOrderCount();
    }

    interface ProductSalesTotal {
        Long getProductId();
        Long getUnitsSold();
    }

    @Query("""
        select count(distinct oi.order.id)
        from OrderItem oi
        where oi.product.id = :productId
          and upper(oi.order.status) not in ('FULFILLED', 'CANCELLED', 'SENT')
        """)
    long countActiveOrders(@Param("productId") Long productId);

    @Query("""
        select oi.product.id as productId, count(distinct oi.order.id) as activeOrderCount
        from OrderItem oi
        where oi.product.id in :productIds
          and upper(oi.order.status) not in ('FULFILLED', 'CANCELLED', 'SENT')
        group by oi.product.id
        """)
    List<ActiveOrderCount> countActiveOrdersByProductIds(@Param("productIds") Collection<Long> productIds);

    boolean existsByProduct_Id(Long productId);

    @Query("""
        select oi.product.id as productId, coalesce(sum(oi.quantity), 0) as unitsSold
        from OrderItem oi
        where oi.seller.id = :sellerId
          and oi.product is not null
          and oi.order.createdAt >= :since
          and upper(oi.order.status) <> 'CANCELLED'
          and upper(coalesce(oi.order.orderType, 'DIRECT')) <> 'AUCTION'
        group by oi.product.id
        """)
    List<ProductSalesTotal> sumDirectUnitsSoldSince(
        @Param("sellerId") Long sellerId,
        @Param("since") LocalDateTime since
    );
}

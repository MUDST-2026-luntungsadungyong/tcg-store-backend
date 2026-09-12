package com.mythix.tcg.seller.persistence;

import com.mythix.tcg.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SellerOrderItemRepository extends JpaRepository<OrderItem, Long> {

    interface ActiveOrderCount {
        Long getProductId();
        Long getActiveOrderCount();
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
}

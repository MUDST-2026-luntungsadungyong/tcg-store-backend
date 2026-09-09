package com.mythix.tcg.order.persistence;

import com.mythix.tcg.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findBySellerId(Long sellerId);

    @Query("SELECT item FROM OrderItem item WHERE item.seller.id = :sellerId AND item.order.id = :orderId")
    List<OrderItem> findBySellerIdAndOrderId(@Param("sellerId") Long sellerId, @Param("orderId") Long orderId);
}

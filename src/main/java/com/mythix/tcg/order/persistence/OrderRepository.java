package com.mythix.tcg.order.persistence;

import com.mythix.tcg.order.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;
import java.math.BigDecimal;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT DISTINCT o FROM Order o JOIN o.items item WHERE item.seller.id = :sellerId ORDER BY o.createdAt DESC")
    Page<Order> findOrdersBySellerId(@Param("sellerId") Long sellerId, Pageable pageable);

    @Query("SELECT DISTINCT o FROM Order o JOIN o.items item WHERE item.seller.id = :sellerId AND o.status = :status ORDER BY o.createdAt DESC")
    Page<Order> findOrdersBySellerIdAndStatus(@Param("sellerId") Long sellerId, @Param("status") String status, Pageable pageable);

    @Query("SELECT DISTINCT o FROM Order o JOIN o.items item WHERE item.seller.id = :sellerId")
    List<Order> findAllOrdersBySellerId(@Param("sellerId") Long sellerId);

    @Query("SELECT DISTINCT o FROM Order o JOIN o.items item WHERE item.seller.id = :sellerId AND o.id = :orderId")
    Optional<Order> findOrderForSeller(@Param("sellerId") Long sellerId, @Param("orderId") Long orderId);

    List<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId);

    List<Order> findByBuyerIdAndStatusOrderByCreatedAtDesc(Long buyerId, String status);

    Page<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId, Pageable pageable);

    Page<Order> findByBuyerIdAndStatusOrderByCreatedAtDesc(Long buyerId, String status, Pageable pageable);

    Optional<Order> findByIdAndBuyerId(Long id, Long buyerId);

    @Query("select coalesce(sum(o.totalAmount), 0) from Order o")
    BigDecimal totalOrderAmount();
    @Query("select coalesce(sum(o.totalAmount), 0) from Order o where o.status in ('PAID','FULFILLED')") BigDecimal paidOrFulfilledGmv();
    @EntityGraph(attributePaths = "buyer")
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = "buyer")
    Page<Order> findAllByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    @EntityGraph(attributePaths = {"buyer", "items", "items.seller"})
    @Query("""
        select distinct o from Order o join o.buyer buyer left join o.items item left join item.seller seller
        where (:query = ''
          or (:searchBy = 'ORDER_NUMBER' and lower(o.orderNumber) like lower(concat('%', :query, '%')))
          or (:searchBy = 'BUYER' and (lower(buyer.fullName) like lower(concat('%', :query, '%')) or lower(buyer.email) like lower(concat('%', :query, '%'))))
          or (:searchBy = 'SELLER' and (lower(seller.fullName) like lower(concat('%', :query, '%')) or lower(seller.email) like lower(concat('%', :query, '%')) or lower(item.shopNameSnapshot) like lower(concat('%', :query, '%')))))
          and (:statusesEmpty = 'Y' or o.status in :statuses)
          and (cast(:fromDate as localdatetime) is null or o.createdAt >= :fromDate)
          and (cast(:toDate as localdatetime) is null or o.createdAt < :toDate)
        order by o.createdAt desc
        """)
    Page<Order> searchAdminOrderLogs(@Param("query") String query, @Param("searchBy") String searchBy, @Param("statuses") List<String> statuses, @Param("statusesEmpty") String statusesEmpty, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate, Pageable pageable);
}

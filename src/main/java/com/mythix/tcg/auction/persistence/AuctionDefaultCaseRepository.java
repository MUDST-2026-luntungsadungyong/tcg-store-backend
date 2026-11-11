package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionDefaultCase;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuctionDefaultCaseRepository extends JpaRepository<AuctionDefaultCase, Long> {
    boolean existsByOrderId(Long orderId);

    Page<AuctionDefaultCase> findByBuyerIdOrSellerId(Long buyerId, Long sellerId, Pageable page);

    List<AuctionDefaultCase> findByBuyerIdAndStatusOrderByDecidedAtAscIdAsc(Long buyerId, String status);

    @Query("select c.auctionId from AuctionDefaultCase c where c.id = :id")
    Optional<Long> findAuctionId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuctionDefaultCase c where c.id = :id")
    Optional<AuctionDefaultCase> findByIdForUpdate(@Param("id") Long id);
}

package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.Auction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuctionRepository extends JpaRepository<Auction, Long>, JpaSpecificationExecutor<Auction> {
    List<Auction> findAllBySellerIdOrderByUpdatedAtDescIdDesc(Long sellerId);
    Optional<Auction> findByIdAndSellerId(Long id, Long sellerId);
    Optional<Auction> findBySellerIdAndCreationKey(Long sellerId, String creationKey);

    @Query("select a.id from Auction a where a.orderId = :orderId")
    Optional<Long> findIdByOrderId(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Auction a where a.id = :id")
    Optional<Auction> findByIdForUpdate(@Param("id") Long id);

    @Query("""
        select a.id from Auction a
        where (a.status = :scheduled and a.startAt <= :now)
           or (a.status = :active and a.endAt <= :now)
        order by a.id
        """)
    List<Long> findDueIds(@Param("scheduled") Auction.Status scheduled, @Param("active") Auction.Status active,
                          @Param("now") Instant now, Pageable pageable);


    Page<Auction> findAllByStatus(Auction.Status status, Pageable pageable);

    List<Auction> findAllByWinnerIdOrderByUpdatedAtDesc(Long winnerId);

    long countByWinnerIdAndStatusIn(Long winnerId, Collection<Auction.Status> statuses);

    List<Auction> findAllBySellerIdAndStatusIn(Long sellerId, Collection<Auction.Status> statuses);

    @Query("select count(a) from Auction a where a.sellerId = :sellerId and a.status in ('COMPLETED', 'BUYOUT_COMPLETED')")
    long countCompletedBySellerId(@Param("sellerId") Long sellerId);
}

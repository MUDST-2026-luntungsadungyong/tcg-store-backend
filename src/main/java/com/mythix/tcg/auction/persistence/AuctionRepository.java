package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.Auction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuctionRepository extends JpaRepository<Auction, Long> {
    List<Auction> findAllBySellerIdOrderByUpdatedAtDescIdDesc(Long sellerId);
    Optional<Auction> findByIdAndSellerId(Long id, Long sellerId);
    Optional<Auction> findBySellerIdAndCreationKey(Long sellerId, String creationKey);

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
}

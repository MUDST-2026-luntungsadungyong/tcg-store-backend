package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionWatchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuctionWatchlistRepository extends JpaRepository<AuctionWatchlist, Long> {

    Optional<AuctionWatchlist> findByUserIdAndAuctionId(Long userId, Long auctionId);

    boolean existsByUserIdAndAuctionId(Long userId, Long auctionId);

    void deleteByUserIdAndAuctionId(Long userId, Long auctionId);

    List<AuctionWatchlist> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT w FROM AuctionWatchlist w JOIN Auction a ON w.auctionId = a.id " +
           "WHERE a.status = 'ACTIVE' AND a.endAt BETWEEN :start AND :end AND w.notified1h = false")
    List<AuctionWatchlist> findDue1HourWatchlists(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT w FROM AuctionWatchlist w JOIN Auction a ON w.auctionId = a.id " +
           "WHERE a.status = 'ACTIVE' AND a.endAt BETWEEN :start AND :end AND w.notified15m = false")
    List<AuctionWatchlist> findDue15MinWatchlists(@Param("start") Instant start, @Param("end") Instant end);
}

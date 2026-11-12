package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionBid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


public interface AuctionBidRepository extends JpaRepository<AuctionBid, Long> {

    List<AuctionBid> findAllByAuctionIdOrderByCreatedAtDescIdDesc(Long auctionId);

    List<AuctionBid> findTop2ByAuctionIdOrderByCreatedAtDescIdDesc(Long auctionId);

    @Query("""
        select b from AuctionBid b
        where b.auctionId = :auctionId and b.bidderId <> :winnerId
          and exists (select u.id from User u where u.id = b.bidderId
              and (u.auctionBannedUntil is null or u.auctionBannedUntil <= :now))
          and not exists (select o.id from AuctionSecondChanceOffer o
              where o.auctionId = :auctionId and o.bidderId = b.bidderId)
        order by b.bidAmount desc, b.createdAt asc, b.id asc
        """)
    List<AuctionBid> findEligibleRunnerUp(Long auctionId, Long winnerId, java.time.Instant now, Pageable pageable);

    @Query("SELECT b FROM AuctionBid b WHERE b.auctionId = :auctionId AND b.bidType = 'PROXY' ORDER BY b.maxBidAmount DESC, b.createdAt ASC")
    List<AuctionBid> findProxyBidsByAuctionId(@Param("auctionId") Long auctionId);

    long countByBidderId(Long bidderId);

    @Query("SELECT COUNT(DISTINCT b.auctionId) FROM AuctionBid b WHERE b.bidderId = :bidderId")
    long countDistinctAuctionsByBidderId(@Param("bidderId") Long bidderId);

    @Query("SELECT DISTINCT b.auctionId FROM AuctionBid b WHERE b.bidderId = :bidderId")
    List<Long> findDistinctAuctionIdsByBidderId(@Param("bidderId") Long bidderId);
}

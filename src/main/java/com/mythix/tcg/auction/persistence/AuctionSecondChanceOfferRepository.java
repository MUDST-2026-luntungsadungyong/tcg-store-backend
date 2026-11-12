package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionSecondChanceOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuctionSecondChanceOfferRepository extends JpaRepository<AuctionSecondChanceOffer, Long> {

    List<AuctionSecondChanceOffer> findAllByAuctionIdOrderByCreatedAtDesc(Long auctionId);

    List<AuctionSecondChanceOffer> findAllByBidderIdAndStatusOrderByCreatedAtDesc(
            Long bidderId, AuctionSecondChanceOffer.OfferStatus status);

    boolean existsByAuctionIdAndStatusAndExpiresAtAfter(
        Long auctionId, AuctionSecondChanceOffer.OfferStatus status, java.time.Instant deadline);

    @org.springframework.data.jpa.repository.Query("select o.auctionId from AuctionSecondChanceOffer o where o.id = :id and o.bidderId = :bidderId")
    Optional<Long> findAuctionId(Long id, Long bidderId);

    Optional<AuctionSecondChanceOffer> findByIdAndBidderId(Long id, Long bidderId);

    Optional<AuctionSecondChanceOffer> findByAuctionIdAndBidderId(Long auctionId, Long bidderId);
}

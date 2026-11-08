package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionCancellationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuctionCancellationRepository extends JpaRepository<AuctionCancellationRequest, Long> {
    @Query("select r.auctionId from AuctionCancellationRequest r where r.id = :id")
    Optional<Long> findAuctionId(@Param("id") Long id);

    List<AuctionCancellationRequest> findAllByOrderByCreatedAtDescIdDesc();
    List<AuctionCancellationRequest> findAllByAuctionIdInOrderByIdDesc(List<Long> auctionIds);
    Optional<AuctionCancellationRequest> findFirstByAuctionIdAndStatusOrderByIdDesc(
        Long auctionId, AuctionCancellationRequest.Status status
    );
}

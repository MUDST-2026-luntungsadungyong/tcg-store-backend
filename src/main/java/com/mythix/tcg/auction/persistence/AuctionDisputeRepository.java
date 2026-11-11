package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionDispute;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface AuctionDisputeRepository extends JpaRepository<AuctionDispute, Long> {
    boolean existsByAuctionId(Long auctionId);
    Page<AuctionDispute> findByBuyerIdOrSellerId(Long buyerId, Long sellerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AuctionDispute d where d.id = :id")
    Optional<AuctionDispute> lock(Long id);
}

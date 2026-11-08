package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionPaymentProof;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionPaymentProofRepository extends JpaRepository<AuctionPaymentProof, Long> {
}

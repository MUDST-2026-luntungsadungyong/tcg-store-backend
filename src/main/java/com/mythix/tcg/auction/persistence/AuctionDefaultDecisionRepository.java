package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionDefaultDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuctionDefaultDecisionRepository extends JpaRepository<AuctionDefaultDecision, Long> {
    List<AuctionDefaultDecision> findByCaseIdOrderByCreatedAtAscIdAsc(Long caseId);
}

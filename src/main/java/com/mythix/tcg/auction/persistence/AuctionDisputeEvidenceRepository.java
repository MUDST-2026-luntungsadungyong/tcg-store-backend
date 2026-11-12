package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionDisputeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface AuctionDisputeEvidenceRepository extends JpaRepository<AuctionDisputeEvidence, Long> {
    interface FileRef { Long getId(); Long getSubmittedBy(); java.time.Instant getCreatedAt(); }

    @Query("select e.id as id, e.submittedBy as submittedBy, e.createdAt as createdAt from AuctionDisputeEvidence e where e.disputeId = :id order by e.id")
    List<FileRef> findRefs(Long id);
    Optional<AuctionDisputeEvidence> findByIdAndDisputeId(Long id, Long disputeId);
}

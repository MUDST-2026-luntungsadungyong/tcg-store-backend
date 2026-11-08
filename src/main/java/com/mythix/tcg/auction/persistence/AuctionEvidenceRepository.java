package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionCancellationEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuctionEvidenceRepository extends JpaRepository<AuctionCancellationEvidence, Long> {
    record FileRef(Long id, Long requestId, String fileName) {}

    @Query("""
        select new com.mythix.tcg.auction.persistence.AuctionEvidenceRepository$FileRef(e.id, e.requestId, e.fileName)
        from AuctionCancellationEvidence e where e.requestId in :requestIds order by e.id
        """)
    List<FileRef> findFileRefs(@Param("requestIds") List<Long> requestIds);

    Optional<AuctionCancellationEvidence> findByIdAndRequestId(Long id, Long requestId);
}

package com.mythix.tcg.auction.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "auction_dispute_evidence")
@Getter
@Setter
@NoArgsConstructor
public class AuctionDisputeEvidence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long disputeId;
    @Column(nullable = false)
    private Long submittedBy;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 100)
    private String contentType;
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;
}

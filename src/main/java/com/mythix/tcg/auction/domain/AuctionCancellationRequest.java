package com.mythix.tcg.auction.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "auction_cancellation_requests")
@Getter
@Setter
@NoArgsConstructor
public class AuctionCancellationRequest {
    public enum Status { PENDING_REVIEW, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long auctionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING_REVIEW;
    @Column(nullable = false, length = 1000)
    private String reason;
    @Column(length = 1000)
    private String decisionReason;
    private Long reviewedBy;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant reviewedAt;
}

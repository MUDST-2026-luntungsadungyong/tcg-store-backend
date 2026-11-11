package com.mythix.tcg.auction.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "auction_disputes")
@Getter
@Setter
@NoArgsConstructor
public class AuctionDispute {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private Long auctionId;
    @Column(nullable = false)
    private Long orderId;
    @Column(nullable = false)
    private Long buyerId;
    @Column(nullable = false)
    private Long sellerId;
    @Column(nullable = false, length = 2000)
    private String reason;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant decidedAt;
    @Column(length = 2000)
    private String decisionReason;
    private Long decidedBy;
}

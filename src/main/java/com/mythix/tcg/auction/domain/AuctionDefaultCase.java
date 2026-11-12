package com.mythix.tcg.auction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "auction_default_cases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionDefaultCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "auction_id", nullable = false)
    private Long auctionId;
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;
    @Column(name = "order_number", nullable = false)
    private String orderNumber;
    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;
    @Column(name = "report_reason", nullable = false, length = 2000)
    private String reportReason;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decision_reason", length = 2000)
    private String decisionReason;
}

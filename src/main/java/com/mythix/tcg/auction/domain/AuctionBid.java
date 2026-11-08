package com.mythix.tcg.auction.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "auction_bids")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionBid {

    public enum BidType { MANUAL, PROXY, BUYOUT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "bidder_id", nullable = false)
    private Long bidderId;

    @Column(name = "bid_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal bidAmount;

    @Column(name = "max_bid_amount", precision = 12, scale = 2)
    private BigDecimal maxBidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "bid_type", length = 20, nullable = false)
    private BidType bidType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

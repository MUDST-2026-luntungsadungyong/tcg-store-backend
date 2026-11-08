package com.mythix.tcg.auction.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "auction_watchlists", uniqueConstraints = {
    @UniqueConstraint(name = "uq_auction_watchlist", columnNames = {"user_id", "auction_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionWatchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "notified_1h", nullable = false)
    @Builder.Default
    private boolean notified1h = false;

    @Column(name = "notified_15m", nullable = false)
    @Builder.Default
    private boolean notified15m = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

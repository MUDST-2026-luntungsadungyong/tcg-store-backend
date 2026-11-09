package com.mythix.tcg.auction.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "auctions")
@Getter
@Setter
@NoArgsConstructor
public class Auction {
    public enum Status {
        DRAFT, SCHEDULED, ACTIVE, ENDED, AWAITING_PAYMENT, COMPLETED, CANCELLED,
        BUYOUT_COMPLETED, RESERVE_NOT_MET, UNSOLD_NO_BIDS, DEFAULTED
    }
    public enum StartMode { NOW, SCHEDULED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;
    private Long productId;
    @Column(nullable = false)
    private int quantity = 1;
    @Column(nullable = false, length = 10)
    private String language = "EN";
    @Column(length = 100)
    private String creationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.DRAFT;

    @Column(length = 100)
    private String cardId;
    private String cardName;
    private String setName;
    @Column(length = 500)
    private String imageUrl;
    @Column(length = 50)
    private String condition;
    @Column(length = 10)
    private String gradingCompany;
    @Column(length = 30)
    private String grade;
    @Column(length = 100)
    private String certNumber;

    @Column(precision = 12, scale = 2)
    private BigDecimal startPrice;
    @Column(precision = 12, scale = 2)
    private BigDecimal reservePrice;
    @Column(precision = 12, scale = 2)
    private BigDecimal buyItNowPrice;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private StartMode startMode;
    private Instant startAt;
    private Instant endAt;
    private Integer durationHours;

    @Column(nullable = false)
    private int bidCount;
    @Column(precision = 12, scale = 2)
    private BigDecimal highestBid;

    private Long leadingBidderId;

    private Long winnerId;
    @Column(precision = 12, scale = 2)
    private BigDecimal winningBid;
    @Column(nullable = false)
    private boolean isReserveMet;

    private Long orderId;
    private Instant paymentDeadline;

    @Column(precision = 5, scale = 4, nullable = false)
    private BigDecimal platformFeeRate = new BigDecimal("0.0500");
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal platformFeeAmount = BigDecimal.ZERO;
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal sellerNetAmount = BigDecimal.ZERO;
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal costBasis = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean stockHeld;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public boolean canCancel(Instant now) {
        return (status == Status.SCHEDULED || status == Status.ACTIVE) && now.isBefore(endAt);
    }
}

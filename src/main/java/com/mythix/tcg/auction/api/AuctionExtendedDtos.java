package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionBid;
import com.mythix.tcg.auction.domain.AuctionSecondChanceOffer;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AuctionExtendedDtos {
    private AuctionExtendedDtos() {}

    @Getter
    @Setter
    public static class BidRequest {
        @NotNull(message = "Bid amount is required")
        @DecimalMin(value = "0.01")
        @Digits(integer = 10, fraction = 2)
        private BigDecimal amount;
    }

    @Getter
    @Setter
    public static class ProxyBidRequest {
        @NotNull(message = "Max bid amount is required")
        @DecimalMin(value = "0.01")
        @Digits(integer = 10, fraction = 2)
        private BigDecimal maxAmount;
    }

    public record BidView(
        Long id,
        Long auctionId,
        BigDecimal bidAmount,
        AuctionBid.BidType bidType,
        Instant createdAt,
        String maskedBidderName,
        boolean isSelf
    ) {}

    public record BuyerAuctionDetailView(
        Long id,
        Auction.Status status,
        Long sellerId,
        String sellerName,
        Double sellerRating,
        long sellerCompletedAuctions,
        Long productId,
        String cardId,
        String cardName,
        String setName,
        String imageUrl,
        String condition,
        int quantity,
        String language,
        String gradingCompany,
        String grade,
        String certNumber,
        String certVerificationUrl,
        BigDecimal startPrice,
        BigDecimal currentPrice,
        BigDecimal nextMinimumBid,
        BigDecimal bidIncrement,
        boolean isReserveMet,
        BigDecimal buyItNowPrice,
        Instant startAt,
        Instant endAt,
        Instant pausedAt,
        Instant serverTime,
        int bidCount,
        boolean isWatching,
        boolean isHighBidder,
        String frontImageUrl,
        String backImageUrl,
        Long winnerId,
        BigDecimal winningBid,
        Long orderId
    ) {}

    public record AuctionSummaryView(
        Long id,
        Auction.Status status,
        Long sellerId,
        String sellerName,
        String cardId,
        String cardName,
        String setName,
        String imageUrl,
        String condition,
        int quantity,
        String language,
        String gradingCompany,
        String grade,
        String certNumber,
        BigDecimal startPrice,
        BigDecimal currentPrice,
        int bidCount,
        BigDecimal buyItNowPrice,
        boolean isReserveMet,
        Instant startAt,
        Instant endAt,
        Instant serverTime,
        boolean isWatching,
        String frontImageUrl,
        String backImageUrl
    ) {}

    public record SellerAnalyticsView(
        BigDecimal grossAuctionVolume,
        BigDecimal totalCost,
        BigDecimal totalPlatformFee,
        BigDecimal netRealizedProfit,
        Double profitMarginPercent,
        long totalCompletedAuctions,
        List<SellerAnalyticsItem> items
    ) {}

    public record SellerAnalyticsItem(
        Long auctionId,
        String cardName,
        BigDecimal startPrice,
        BigDecimal finalPrice,
        BigDecimal costBasis,
        BigDecimal platformFee,
        BigDecimal netProfit,
        Instant closedAt
    ) {}

    public record BuyerPortfolioView(
        long bidsPlaced,
        long auctionsWon,
        Double winRatePercent,
        BigDecimal totalInvested,
        List<String> badges,
        List<WonAuctionItem> wonItems
    ) {}

    public record WonAuctionItem(
        Long auctionId,
        Long orderId,
        String orderNumber,
        String orderStatus,
        String cardName,
        String imageUrl,
        String condition,
        int quantity,
        String language,
        String gradingCompany,
        String grade,
        BigDecimal finalPrice,
        Instant wonAt,
        boolean isPaid
    ) {}

    public record SecondChanceOfferView(
        Long id,
        Long auctionId,
        String cardName,
        String imageUrl,
        int quantity,
        BigDecimal offerPrice,
        AuctionSecondChanceOffer.OfferStatus status,
        Instant expiresAt,
        Instant createdAt
    ) {}

    public record WatchlistStatusResponse(
        Long auctionId,
        boolean isWatching
    ) {}

    public record SettlementSummaryView(
        Long auctionId,
        String cardName,
        BigDecimal finalPrice,
        BigDecimal platformFeeRate,
        BigDecimal platformFeeAmount,
        BigDecimal sellerNetAmount,
        BigDecimal costBasis,
        Auction.Status status,
        Instant closedAt
    ) {}
}

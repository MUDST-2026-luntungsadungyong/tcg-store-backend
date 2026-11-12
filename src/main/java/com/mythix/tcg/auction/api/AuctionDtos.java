package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionCancellationRequest;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AuctionDtos {
    private AuctionDtos() {}

    @Getter
    @Setter
    public static class DraftForm {
        private Long productId;
        private String cardId;
        private Integer quantity;
        private String language;
        private String condition;
        private String gradingCompany;
        private String grade;
        private String certNumber;
        private BigDecimal startPrice;
        private BigDecimal reservePrice;
        private BigDecimal buyItNowPrice;
        private Auction.StartMode startMode;
        private Instant startAt;
        private Integer durationHours;
        private MultipartFile frontImage;
        private MultipartFile backImage;
    }

    public record InventoryItem(Long productId, String cardId, String cardName, String setName, String imageUrl,
                                String cardNumber, String rarity, String condition, String language, String status,
                                int availableQuantity, boolean collectionMatch) {}

    public record AuctionView(Long id, Auction.Status status, Long productId, String cardId, String cardName,
                              String setName, String imageUrl, String condition, int quantity, String language, String gradingCompany, String grade,
                              String certNumber, BigDecimal startPrice, BigDecimal reservePrice,
                              BigDecimal buyItNowPrice, Auction.StartMode startMode, Instant startAt, Instant endAt,
                              Integer durationHours, int bidCount, BigDecimal highestBid, Instant updatedAt,
                              Instant serverTime, String frontImageUrl, String backImageUrl,
                              CancellationView cancellationRequest,
                              Long winnerId, BigDecimal winningBid, Long orderId) {}

    public record CancellationView(Long id, Long auctionId, Auction.Status auctionStatus, String cardName,
                                   Long productId, String imageUrl, String condition, BigDecimal highestBid, Instant endAt,
                                   Long sellerId, String sellerName, int bidCount, String reason,
                                   AuctionCancellationRequest.Status status, String decisionReason, Instant createdAt,
                                   Instant reviewedAt, List<EvidenceView> evidence) {}

    public record EvidenceView(Long id, String fileName, String url) {}
    public record RejectForm(String reason) {}
    public record ImageView(String contentType, byte[] content) {}
}

package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.api.AuctionExtendedDtos.*;
import com.mythix.tcg.auction.application.AuctionAnalyticsService;
import com.mythix.tcg.auction.application.AuctionBidService;
import com.mythix.tcg.auction.application.AuctionDiscoveryService;
import com.mythix.tcg.auction.application.AuctionSettlementService;
import com.mythix.tcg.auction.application.AuctionWatchlistService;
import com.mythix.tcg.auction.domain.AuctionPhoto;
import com.mythix.tcg.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BuyerAuctionController {

    private final AuctionDiscoveryService discoveryService;
    private final AuctionBidService bidService;
    private final AuctionWatchlistService watchlistService;
    private final AuctionSettlementService settlementService;
    private final AuctionAnalyticsService analyticsService;

    @GetMapping("/auctions")
    public ResponseEntity<Page<AuctionSummaryView>> getAuctions(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ending_soon") String sort,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Long currentUserId = user != null ? user.userId() : null;

        Sort sorting = switch (sort.toLowerCase()) {
            case "hot_bids" -> Sort.by(Sort.Direction.DESC, "bidCount");
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "highestBid", "startPrice");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "highestBid", "startPrice");
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> Sort.by(Sort.Direction.ASC, "endAt");
        };

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)), sorting);
        return ResponseEntity.ok(discoveryService.searchAuctions(
            keyword, tab, condition, minPrice, maxPrice, pageable, currentUserId));
    }

    @GetMapping("/auctions/{id}/photos/{side}")
    public ResponseEntity<byte[]> photo(@PathVariable Long id, @PathVariable String side) {
        AuctionPhoto.Side photoSide = switch (side) {
            case "front" -> AuctionPhoto.Side.FRONT;
            case "back" -> AuctionPhoto.Side.BACK;
            default -> throw com.mythix.tcg.auction.application.AuctionException.notFound("Auction photo not found");
        };
        var image = discoveryService.photo(id, photoSide);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
            .header("X-Content-Type-Options", "nosniff").body(image.content());
    }

    @GetMapping("/auctions/{id}")
    public ResponseEntity<BuyerAuctionDetailView> getAuctionDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Long currentUserId = user != null ? user.userId() : null;
        return ResponseEntity.ok(discoveryService.getBuyerAuctionDetail(id, currentUserId));
    }

    @GetMapping("/auctions/{id}/bids")
    public ResponseEntity<List<BidView>> getBidHistory(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Long currentUserId = user != null ? user.userId() : null;
        return ResponseEntity.ok(bidService.getBidHistory(id, currentUserId));
    }

    @PostMapping("/auctions/{id}/bids")
    public ResponseEntity<BidView> placeManualBid(
            @PathVariable Long id,
            @Valid @RequestBody BidRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(bidService.placeManualBid(id, user.userId(), request.getAmount()));
    }

    @PostMapping("/auctions/{id}/proxy-bid")
    public ResponseEntity<BidView> placeProxyBid(
            @PathVariable Long id,
            @Valid @RequestBody ProxyBidRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(bidService.placeProxyBid(id, user.userId(), request.getMaxAmount()));
    }

    @PostMapping("/auctions/{id}/buyout")
    public ResponseEntity<SettlementSummaryView> executeBuyout(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(bidService.executeBuyout(id, user.userId()));
    }

    @PostMapping("/auctions/{id}/watch")
    public ResponseEntity<WatchlistStatusResponse> toggleWatchlist(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(watchlistService.toggleWatchlist(user.userId(), id));
    }

    @GetMapping("/user/watchlist")
    public ResponseEntity<List<AuctionSummaryView>> getUserWatchlist(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(watchlistService.getUserWatchlist(user.userId()));
    }

    @GetMapping("/user/auction-portfolio")
    public ResponseEntity<BuyerPortfolioView> getAuctionPortfolio(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(analyticsService.getBuyerPortfolio(user.userId()));
    }

    @PostMapping(path = "/orders/{orderId}/payment-proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> submitPaymentProof(@PathVariable Long orderId, @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user) {
        settlementService.submitPaymentProof(orderId, user.userId(), file);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/orders/{orderId}/payment-proof")
    public ResponseEntity<byte[]> getPaymentProof(@PathVariable Long orderId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        var proof = settlementService.getPaymentProof(orderId, user.userId());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(proof.getContentType()))
            .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
            .body(proof.getContent());
    }

    @GetMapping("/user/auction-second-chance-offers")
    public List<SecondChanceOfferView> getPendingOffers(@AuthenticationPrincipal AuthenticatedUser user) {
        return settlementService.getPendingOffers(user.userId());
    }

    @PostMapping("/auctions/second-chance/{offerId}/respond")
    public ResponseEntity<Map<String, String>> respondSecondChance(
            @PathVariable Long offerId,
            @RequestParam boolean accept,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        settlementService.respondSecondChanceOffer(offerId, user.userId(), accept);
        return ResponseEntity.ok(Map.of(
            "status", "SUCCESS",
            "message", accept ? "Offer accepted. Order created." : "Offer rejected."
        ));
    }
}

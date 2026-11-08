package com.mythix.tcg.auction.application;

import com.mythix.tcg.auction.api.AuctionExtendedDtos.*;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.persistence.AuctionBidRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.mythix.tcg.auction.domain.Auction.Status.*;

@Service
@RequiredArgsConstructor
public class AuctionAnalyticsService {

    private final AuctionRepository auctionRepository;
    private final AuctionBidRepository bidRepository;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public SellerAnalyticsView getSellerAnalytics(Long sellerId) {
        List<Auction.Status> completedStatuses = List.of(COMPLETED);
        List<Auction> sellerAuctions = auctionRepository.findAllBySellerIdAndStatusIn(sellerId, completedStatuses);

        BigDecimal grossVolume = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal netProfit = BigDecimal.ZERO;

        List<SellerAnalyticsItem> items = new ArrayList<>();

        for (Auction a : sellerAuctions) {
            BigDecimal finalPrice = a.getWinningBid() != null ? a.getWinningBid() : BigDecimal.ZERO;
            BigDecimal cost = a.getCostBasis() != null ? a.getCostBasis() : BigDecimal.ZERO;
            BigDecimal fee = a.getPlatformFeeAmount() != null ? a.getPlatformFeeAmount() : BigDecimal.ZERO;
            BigDecimal profit = finalPrice.subtract(cost).subtract(fee);

            grossVolume = grossVolume.add(finalPrice);
            totalCost = totalCost.add(cost);
            totalFees = totalFees.add(fee);
            netProfit = netProfit.add(profit);

            items.add(new SellerAnalyticsItem(
                a.getId(),
                a.getCardName(),
                a.getStartPrice(),
                finalPrice,
                cost,
                fee,
                profit,
                a.getUpdatedAt()
            ));
        }

        Double marginPercent = 0.0;
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = netProfit.multiply(new BigDecimal("100"))
                .divide(totalCost, 2, RoundingMode.HALF_UP)
                .doubleValue();
        }

        return new SellerAnalyticsView(
            grossVolume,
            totalCost,
            totalFees,
            netProfit,
            marginPercent,
            sellerAuctions.size(),
            items
        );
    }

    @Transactional(readOnly = true)
    public BuyerPortfolioView getBuyerPortfolio(Long buyerId) {
        long bidsPlaced = bidRepository.countByBidderId(buyerId);
        long distinctParticipated = bidRepository.countDistinctAuctionsByBidderId(buyerId);

        List<Auction.Status> wonStatuses = List.of(AWAITING_PAYMENT, COMPLETED, BUYOUT_COMPLETED);
        List<Auction> wonAuctions = auctionRepository.findAllByWinnerIdOrderByUpdatedAtDesc(buyerId);

        long auctionsWon = wonAuctions.stream()
            .filter(a -> wonStatuses.contains(a.getStatus()))
            .count();

        Double winRate = 0.0;
        if (distinctParticipated > 0) {
            winRate = BigDecimal.valueOf(auctionsWon * 100.0)
                .divide(BigDecimal.valueOf(distinctParticipated), 1, RoundingMode.HALF_UP)
                .doubleValue();
        }

        BigDecimal totalInvested = BigDecimal.ZERO;
        List<WonAuctionItem> wonItems = new ArrayList<>();

        Set<Long> orderIds = new HashSet<>();
        for (Auction a : wonAuctions) {
            if (a.getOrderId() != null) orderIds.add(a.getOrderId());
        }
        Map<Long, Order> orderMap = new HashMap<>();
        for (Order o : orderRepository.findAllById(orderIds)) {
            orderMap.put(o.getId(), o);
        }

        for (Auction a : wonAuctions) {
            if (!wonStatuses.contains(a.getStatus())) continue;

            BigDecimal price = a.getWinningBid() != null ? a.getWinningBid() : BigDecimal.ZERO;


            Order order = a.getOrderId() != null ? orderMap.get(a.getOrderId()) : null;
            String orderNumber = order != null ? order.getOrderNumber() : "N/A";
            String orderStatus = order != null ? order.getStatus() : "PENDING";
            boolean isPaid = order != null && ("CONFIRMED".equalsIgnoreCase(order.getStatus())
                || "SENT".equalsIgnoreCase(order.getStatus())
                || "FULFILLED".equalsIgnoreCase(order.getStatus()));
            if (isPaid) {
                totalInvested = totalInvested.add(price);
            }

            wonItems.add(new WonAuctionItem(
                a.getId(),
                a.getOrderId(),
                orderNumber,
                orderStatus,
                a.getCardName(),
                a.getImageUrl(),
                a.getCondition(),
                a.getGradingCompany(),
                a.getGrade(),
                price,
                a.getUpdatedAt(),
                a.getPaymentDeadline(),
                isPaid
            ));
        }

        List<String> badges = new ArrayList<>();
        if (auctionsWon >= 1) badges.add("First Win");
        if (auctionsWon >= 3) badges.add("Master Bidder");
        if (totalInvested.compareTo(new BigDecimal("10000.00")) >= 0) badges.add("High-Roller Collector");
        if (bidsPlaced >= 10) badges.add("Seasoned Bidder");

        return new BuyerPortfolioView(
            bidsPlaced,
            auctionsWon,
            winRate,
            totalInvested,
            badges,
            wonItems
        );
    }
}

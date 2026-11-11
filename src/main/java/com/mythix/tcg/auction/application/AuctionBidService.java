package com.mythix.tcg.auction.application;

import com.mythix.tcg.auction.api.AuctionExtendedDtos.*;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionBid;
import com.mythix.tcg.auction.persistence.AuctionBidRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.application.AuctionUserLock;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.auction.api.AuctionLiveUpdates;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.mythix.tcg.auction.application.AuctionException.*;
import static com.mythix.tcg.auction.domain.Auction.Status.*;
import static com.mythix.tcg.auction.domain.AuctionBid.BidType.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionBidService {



    private final AuctionRepository auctionRepository;
    private final AuctionCommissionService commission;
    private final ApplicationEventPublisher events;
    private final AuctionBidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuctionUserLock auctionUserLock;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final NotificationService notificationService;

    public static BigDecimal calculateIncrement(BigDecimal currentAmount) {
        if (currentAmount == null || currentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("20.00");
        }
        if (currentAmount.compareTo(new BigDecimal("500.00")) < 0) {
            return new BigDecimal("20.00");
        } else if (currentAmount.compareTo(new BigDecimal("2000.00")) < 0) {
            return new BigDecimal("50.00");
        } else if (currentAmount.compareTo(new BigDecimal("10000.00")) < 0) {
            return new BigDecimal("100.00");
        } else {
            return new BigDecimal("500.00");
        }
    }

    public static BigDecimal getNextMinimumBid(Auction auction) {
        if (auction.getHighestBid() == null || auction.getBidCount() == 0) {
            return auction.getStartPrice() != null ? auction.getStartPrice() : new BigDecimal("1.00");
        }
        BigDecimal increment = calculateIncrement(auction.getHighestBid());
        return auction.getHighestBid().add(increment);
    }

    @Transactional
    public BidView placeManualBid(Long auctionId, Long bidderId, BigDecimal amount) {
        Instant now = Instant.now();
        Auction auction = lockAndValidateAuction(auctionId, bidderId, now);

        BigDecimal minRequired = getNextMinimumBid(auction);
        if (amount.compareTo(minRequired) < 0) {
            throw invalid("Bid amount must be at least " + minRequired.toPlainString() + " THB");
        }

        BigDecimal buyItNowPrice = auction.getBuyItNowPrice();
        if (buyItNowPrice != null && amount.compareTo(buyItNowPrice) >= 0) {
            AuctionBid buyoutBid = processBuyout(auction, bidderId, buyItNowPrice, now).bid();
            User bidder = userRepository.findById(bidderId).orElse(null);
            return toBidView(buyoutBid, bidder, true);
        }

        Long previousHighBidderId = auction.getLeadingBidderId();

        // Check if there is an active proxy bid that can outbid this manual bid
        List<AuctionBid> proxyBids = eligibleLeadingProxies(auction, now);
        AuctionBid activeProxy = null;
        for (AuctionBid pb : proxyBids) {
            if (!pb.getBidderId().equals(bidderId) && pb.getMaxBidAmount() != null
                && pb.getMaxBidAmount().compareTo(amount) >= 0) {
                activeProxy = pb;
                break;
            }
        }

        if (activeProxy != null) {
            BigDecimal proxyIncrement = calculateIncrement(amount);
            BigDecimal counterAmount = amount.add(proxyIncrement);
            if (counterAmount.compareTo(activeProxy.getMaxBidAmount()) > 0) {
                counterAmount = activeProxy.getMaxBidAmount();
            }

            if (buyItNowPrice != null && counterAmount.compareTo(buyItNowPrice) >= 0) {
                AuctionBid manualBid = bidRepository.save(AuctionBid.builder()
                    .auctionId(auctionId)
                    .bidderId(bidderId)
                    .bidAmount(amount)
                    .bidType(MANUAL)
                    .createdAt(now)
                    .build());
                processBuyout(auction, activeProxy.getBidderId(), buyItNowPrice, now);
                notifyOutbid(bidderId, auction);
                User bidder = userRepository.findById(bidderId).orElse(null);
                return toBidView(manualBid, bidder, true);
            }

            // First record the bidder's manual bid
            AuctionBid manualBid = bidRepository.save(AuctionBid.builder()
                .auctionId(auctionId)
                .bidderId(bidderId)
                .bidAmount(amount)
                .bidType(MANUAL)
                .createdAt(now)
                .build());

            // Then proxy bids back automatically
            bidRepository.save(AuctionBid.builder()
                .auctionId(auctionId)
                .bidderId(activeProxy.getBidderId())
                .bidAmount(counterAmount)
                .maxBidAmount(activeProxy.getMaxBidAmount())
                .bidType(PROXY)
                .createdAt(now)
                .build());

            auction.setHighestBid(counterAmount);
            auction.setLeadingBidderId(activeProxy.getBidderId());
            auction.setBidCount(auction.getBidCount() + 2);
            updateReserveMetStatus(auction);
            auction.setUpdatedAt(now);
            auctionRepository.save(auction);
            events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

            // Notify manual bidder that they were outbid immediately
            notifyOutbid(bidderId, auction);

            User bidder = userRepository.findById(bidderId).orElse(null);
            return toBidView(manualBid, bidder, true);
        }

        // Normal successful manual bid
        AuctionBid bid = bidRepository.save(AuctionBid.builder()
            .auctionId(auctionId)
            .bidderId(bidderId)
            .bidAmount(amount)
            .bidType(MANUAL)
            .createdAt(now)
            .build());

        auction.setHighestBid(amount);
        auction.setLeadingBidderId(bidderId);
        auction.setBidCount(auction.getBidCount() + 1);
        updateReserveMetStatus(auction);
        auction.setUpdatedAt(now);
        auctionRepository.save(auction);
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

        // Notify previous high bidder if different
        if (previousHighBidderId != null && !previousHighBidderId.equals(bidderId)) {
            notifyOutbid(previousHighBidderId, auction);
        }

        User bidder = userRepository.findById(bidderId).orElse(null);
        return toBidView(bid, bidder, true);
    }

    @Transactional
    public BidView placeProxyBid(Long auctionId, Long bidderId, BigDecimal maxAmount) {
        Instant now = Instant.now();
        Auction auction = lockAndValidateAuction(auctionId, bidderId, now);

        List<AuctionBid> existingProxies = eligibleLeadingProxies(auction, now);
        if (bidderId.equals(auction.getLeadingBidderId())) {
            var leadingProxy = existingProxies.stream()
                .filter(bid -> bidderId.equals(bid.getBidderId()) && bid.getMaxBidAmount() != null
                    && bid.getBidAmount().compareTo(auction.getHighestBid()) == 0)
                .findFirst();
            if (leadingProxy.isPresent()) {
                AuctionBid bid = leadingProxy.get();
                if (maxAmount.compareTo(bid.getMaxBidAmount()) < 0) {
                    throw invalid("An active proxy ceiling can only be increased");
                }
                bid.setMaxBidAmount(maxAmount);
                return toBidView(bid, null, true);
            }
        }

        BigDecimal minRequired = getNextMinimumBid(auction);
        if (maxAmount.compareTo(minRequired) < 0) {
            throw invalid("Max bid amount must be at least " + minRequired.toPlainString() + " THB");
        }

        BigDecimal buyItNowPrice = auction.getBuyItNowPrice();
        if (buyItNowPrice != null && maxAmount.compareTo(buyItNowPrice) >= 0) {
            AuctionBid buyoutBid = processBuyout(auction, bidderId, buyItNowPrice, now).bid();
            User bidder = userRepository.findById(bidderId).orElse(null);
            return toBidView(buyoutBid, bidder, true);
        }

        Long previousHighBidderId = auction.getLeadingBidderId();

        // Check if there is already an existing proxy bid by another bidder
        AuctionBid existingCompetitorProxy = null;
        for (AuctionBid pb : existingProxies) {
            if (!pb.getBidderId().equals(bidderId) && pb.getMaxBidAmount() != null) {
                existingCompetitorProxy = pb;
                break;
            }
        }

        BigDecimal effectiveBidAmount;
        if (existingCompetitorProxy != null) {
            BigDecimal compMax = existingCompetitorProxy.getMaxBidAmount();
            if (maxAmount.compareTo(compMax) > 0) {
                // New bidder beats the competitor proxy
                BigDecimal inc = calculateIncrement(compMax);
                effectiveBidAmount = compMax.add(inc).max(minRequired);
                if (effectiveBidAmount.compareTo(maxAmount) > 0) {
                    effectiveBidAmount = maxAmount;
                }
            } else {
                // Competitor proxy still wins or ties (first-come first-served)
                BigDecimal inc = calculateIncrement(maxAmount);
                BigDecimal compCounter = maxAmount.add(inc);
                if (compCounter.compareTo(compMax) > 0) {
                    compCounter = compMax;
                }

                if (buyItNowPrice != null && compCounter.compareTo(buyItNowPrice) >= 0) {
                    AuctionBid underBid = bidRepository.save(AuctionBid.builder()
                        .auctionId(auctionId)
                        .bidderId(bidderId)
                        .bidAmount(maxAmount)
                        .maxBidAmount(maxAmount)
                        .bidType(PROXY)
                        .createdAt(now)
                        .build());

                    processBuyout(auction, existingCompetitorProxy.getBidderId(), buyItNowPrice, now);
                    notifyOutbid(bidderId, auction);
                    User bidder = userRepository.findById(bidderId).orElse(null);
                    return toBidView(underBid, bidder, true);
                }

                // Save this outbid proxy attempt
                AuctionBid underBid = bidRepository.save(AuctionBid.builder()
                    .auctionId(auctionId)
                    .bidderId(bidderId)
                    .bidAmount(maxAmount)
                    .maxBidAmount(maxAmount)
                    .bidType(PROXY)
                    .createdAt(now)
                    .build());

                // Save competitor's auto response
                bidRepository.save(AuctionBid.builder()
                    .auctionId(auctionId)
                    .bidderId(existingCompetitorProxy.getBidderId())
                    .bidAmount(compCounter)
                    .maxBidAmount(compMax)
                    .bidType(PROXY)
                    .createdAt(now)
                    .build());

                auction.setHighestBid(compCounter);
                auction.setLeadingBidderId(existingCompetitorProxy.getBidderId());
                auction.setBidCount(auction.getBidCount() + 2);
                updateReserveMetStatus(auction);
                auction.setUpdatedAt(now);
                auctionRepository.save(auction);
                events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

                notifyOutbid(bidderId, auction);
                User bidder = userRepository.findById(bidderId).orElse(null);
                return toBidView(underBid, bidder, true);
            }
        } else {
            // No competing proxy, place bid at minRequired
            effectiveBidAmount = minRequired;
        }

        AuctionBid bid = bidRepository.save(AuctionBid.builder()
            .auctionId(auctionId)
            .bidderId(bidderId)
            .bidAmount(effectiveBidAmount)
            .maxBidAmount(maxAmount)
            .bidType(PROXY)
            .createdAt(now)
            .build());

        auction.setHighestBid(effectiveBidAmount);
        auction.setLeadingBidderId(bidderId);
        auction.setBidCount(auction.getBidCount() + 1);
        updateReserveMetStatus(auction);
        auction.setUpdatedAt(now);
        auctionRepository.save(auction);
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

        if (previousHighBidderId != null && !previousHighBidderId.equals(bidderId)) {
            notifyOutbid(previousHighBidderId, auction);
        }

        User bidder = userRepository.findById(bidderId).orElse(null);
        return toBidView(bid, bidder, true);
    }

    record BuyoutOutcome(AuctionBid bid, SettlementSummaryView summary) {}

    @Transactional
    public SettlementSummaryView executeBuyout(Long auctionId, Long buyerId) {
        Instant now = Instant.now();
        Auction auction = lockAndValidateAuction(auctionId, buyerId, now);

        if (auction.getBuyItNowPrice() == null) {
            throw invalid("This auction does not have a Buy-It-Now option enabled");
        }

        BigDecimal buyoutPrice = auction.getBuyItNowPrice();
        if (auction.getHighestBid() != null && buyoutPrice.compareTo(auction.getHighestBid()) <= 0) {
            throw conflict("Buy-It-Now is no longer available at this price");
        }

        return processBuyout(auction, buyerId, buyoutPrice, now).summary();
    }

    private BuyoutOutcome processBuyout(Auction auction, Long buyerId, BigDecimal buyoutPrice, Instant now) {
        // 1. Create buyout bid record
        AuctionBid bid = bidRepository.save(AuctionBid.builder()
            .auctionId(auction.getId())
            .bidderId(buyerId)
            .bidAmount(buyoutPrice)
            .bidType(BUYOUT)
            .createdAt(now)
            .build());

        // 2. Calculate platform fee and seller net amount
        var policy = commission.policy();
        BigDecimal fee = policy.feeFor(buyoutPrice);
        BigDecimal sellerNet = buyoutPrice.subtract(fee);

        // Snapshot cost basis
        BigDecimal costBasis = BigDecimal.ZERO;
        Product product = null;
        if (auction.getProductId() != null) {
            product = productRepository.findByIdForUpdate(auction.getProductId()).orElse(null);
            if (product != null && product.getCost() != null) {
                costBasis = product.getCost().multiply(BigDecimal.valueOf(auction.getQuantity()));
            }
        }
        if (auction.getProductId() != null && (product == null || !auction.isStockHeld() || product.getAuctionReserved() < auction.getQuantity())) {
            throw conflict("Auction stock is no longer held");
        }

        User buyer = userRepository.findById(buyerId)
            .orElseThrow(() -> notFound("Buyer account not found"));
        User seller = userRepository.findById(auction.getSellerId())
            .orElseThrow(() -> notFound("Seller account not found"));

        // 3. Create Order
        String orderNumber = "AUC-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 900 + 100);
        Order order = Order.builder()
            .orderNumber(orderNumber)
            .buyer(buyer)
            .orderType("AUCTION")
            .status("AWAITING_BUYER_CONTACT")
            .subtotalAmount(buyoutPrice)
            .totalAmount(buyoutPrice)
            .platformFee(fee)
            .sellerNetAmount(sellerNet)
            .createdAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
            .updatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
            .build();
        Order savedOrder = orderRepository.save(order);

        if (product != null) {
            // Move the lot from auction holds to order reservations
            product.setAuctionReserved(Math.max(0, product.getAuctionReserved() - auction.getQuantity()));
            product.setReserved(product.getReserved() + auction.getQuantity());
            productRepository.save(product);
        }
        OrderItem item = OrderItem.builder()
                .order(savedOrder)
                .product(product)
                .seller(seller)
                .cardId(auction.getCardId() != null ? auction.getCardId() : "N/A")
                .productNameSnapshot(auction.getCardName() != null ? auction.getCardName() : "Card")
                .shopNameSnapshot(seller.getFullName())
                .priceAtPurchase(buyoutPrice)
                .costAtPurchase(product != null && product.getCost() != null ? product.getCost() : BigDecimal.ZERO)
                .quantity(auction.getQuantity())
                .build();
        orderItemRepository.save(item);

        // 4. Update Auction state
        auction.setStatus(AWAITING_BUYER_CONTACT);
        auction.setWinnerId(buyerId);
        auction.setWinningBid(buyoutPrice);
        auction.setHighestBid(buyoutPrice);
        auction.setLeadingBidderId(buyerId);
        auction.setBidCount(auction.getBidCount() + 1);
        auction.setReserveMet(true);
        auction.setOrderId(savedOrder.getId());
        auction.setPlatformFeeRate(policy.rate());
        auction.setPlatformFeeAmount(fee);
        auction.setSellerNetAmount(sellerNet);
        auction.setCostBasis(costBasis);
        auction.setStockHeld(false);
        auction.setUpdatedAt(now);
        auctionRepository.save(auction);
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

        // 5. Send notifications
        sendAuctionNotification(buyerId, NotificationTypes.AUCTION_WON,
            "คุณทำการซื้อขาด (Buy-It-Now) รายการ '" + auction.getCardName()
                + "' สำเร็จ กรุณาติดต่อร้านค้าเพื่อดำเนินการชำระเงินและจัดส่ง",
            "/orders/" + savedOrder.getId());

        sendAuctionNotification(auction.getSellerId(), NotificationTypes.AUCTION_ENDED_SELLER,
            "รายการประมูล '" + auction.getCardName() + "' ถูกซื้อขาดในราคา ฿" + buyoutPrice.toPlainString()
                + " กรุณารอผู้ซื้อเพื่อตกลงการชำระเงินและจัดส่ง",
            "/workspace/orders?orderId=" + savedOrder.getId());

        SettlementSummaryView summary = new SettlementSummaryView(
            auction.getId(),
            auction.getCardName(),
            buyoutPrice,
            policy.rate(),
            fee,
            sellerNet,
            costBasis,
            AWAITING_BUYER_CONTACT,
            now
        );
        return new BuyoutOutcome(bid, summary);
    }

    @Transactional(readOnly = true)
    public List<BidView> getBidHistory(Long auctionId, Long currentUserId) {
        auctionRepository.findById(auctionId).filter(auction -> auction.getStatus() != Auction.Status.DRAFT)
            .orElseThrow(() -> notFound("Auction not found"));
        List<AuctionBid> bids = bidRepository.findAllByAuctionIdOrderByCreatedAtDescIdDesc(auctionId);
        if (bids.isEmpty()) {
            return List.of();
        }

        Set<Long> bidderIds = new HashSet<>();
        for (AuctionBid b : bids) {
            bidderIds.add(b.getBidderId());
        }
        Map<Long, User> userMap = new HashMap<>();
        for (User u : userRepository.findAllById(bidderIds)) {
            userMap.put(u.getId(), u);
        }

        return bids.stream().map(b -> {
            boolean isSelf = currentUserId != null && currentUserId.equals(b.getBidderId());
            User bidder = userMap.get(b.getBidderId());
            return toBidView(b, bidder, isSelf);
        }).toList();
    }

    private Auction lockAndValidateAuction(Long auctionId, Long userId, Instant now) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
            .orElseThrow(() -> notFound("Auction not found"));

        if (auction.getSellerId().equals(userId)) {
            throw forbidden("You cannot bid on your own auction");
        }

        // Lock every potentially participating user in stable order, after the auction.
        // This serializes a concurrent penalty decision without introducing cross-auction lock inversions.
        var userIds = new java.util.TreeSet<Long>();
        userIds.add(userId);
        bidRepository.findProxyBidsByAuctionId(auctionId).stream()
            .filter(b -> b.getBidderId().equals(auction.getLeadingBidderId()))
            .forEach(b -> userIds.add(b.getBidderId()));
        User user = null;
        for (Long id : userIds) {
            User locked = auctionUserLock.lock(id).orElseThrow(() -> notFound("User not found"));
            if (id.equals(userId)) user = locked;
        }

        if (user.isAuctionBanned()) {
            throw forbidden("Your account is currently banned from participating in auctions until " + user.getAuctionBannedUntil());
        }

        if (auction.getStatus() != ACTIVE) {
            throw conflict("Auction is not active (current status: " + auction.getStatus() + ")");
        }

        if (!Instant.now().isBefore(auction.getEndAt())) {
            throw conflict("Auction has already ended");
        }

        return auction;
    }


    private List<AuctionBid> eligibleLeadingProxies(Auction auction, Instant now) {
        Long leader = auction.getLeadingBidderId();
        if (leader == null) return List.of();
        User user = userRepository.findById(leader).orElse(null);
        if (user == null || user.isAuctionBanned()) return List.of();
        return bidRepository.findProxyBidsByAuctionId(auction.getId()).stream()
            .filter(b -> leader.equals(b.getBidderId()))
            .toList();
    }

    private void updateReserveMetStatus(Auction auction) {
        if (auction.getReservePrice() != null && auction.getHighestBid() != null) {
            if (auction.getHighestBid().compareTo(auction.getReservePrice()) >= 0) {
                auction.setReserveMet(true);
            }
        } else if (auction.getHighestBid() != null) {
            auction.setReserveMet(true);
        }
    }

    private void notifyOutbid(Long outbidUserId, Auction auction) {
        sendAuctionNotification(outbidUserId, NotificationTypes.AUCTION_OUTBID,
            "มีผู้เสนอราคาสูงกว่าคุณในรายการประมูล '" + auction.getCardName() + "' (ราคาปัจจุบัน ฿"
                + (auction.getHighestBid() != null ? auction.getHighestBid().toPlainString() : "0") + ")",
            "/auctions/" + auction.getId());
    }

    private void sendAuctionNotification(Long userId, String type, String message, String route) {
        try {
            userRepository.findById(userId).ifPresent(recipient ->
                notificationService.sendAuctionNotification(recipient, type, "Auction Update", message, route)
            );
        } catch (Exception e) {
            log.warn("Failed to deliver auction notification to user {}: {}", userId, e.getMessage());
        }
    }

    private static BidView toBidView(AuctionBid bid, User user, boolean isSelf) {
        String masked = maskIdentity(user, isSelf);
        return new BidView(
            bid.getId(),
            bid.getAuctionId(),
            bid.getBidAmount(),
            bid.getBidType(),
            bid.getCreatedAt(),
            masked,
            isSelf
        );
    }

    public static String maskIdentity(User user, boolean isSelf) {
        if (isSelf) {
            return "You";
        }
        if (user == null) {
            return "u***r";
        }
        String name = user.getFullName();
        if (name == null || name.isBlank()) {
            name = user.getEmail();
        }
        if (name == null || name.isBlank()) {
            return "u***r";
        }
        String clean = name.trim();
        if (clean.length() <= 2) {
            return clean.charAt(0) + "***";
        }
        return clean.charAt(0) + "***" + clean.charAt(clean.length() - 1);
    }
}

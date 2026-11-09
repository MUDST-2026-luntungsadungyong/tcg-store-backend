package com.mythix.tcg.auction.application;

import com.mythix.tcg.auction.api.AuctionExtendedDtos.SecondChanceOfferView;
import com.mythix.tcg.auction.api.AuctionExtendedDtos.SettlementSummaryView;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionBid;
import com.mythix.tcg.auction.domain.AuctionSecondChanceOffer;
import com.mythix.tcg.auction.persistence.AuctionBidRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.auction.persistence.AuctionSecondChanceOfferRepository;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.domain.OrderStatusLog;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.order.persistence.OrderStatusLogRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.auction.api.AuctionLiveUpdates;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.mythix.tcg.seller.application.SellerProductImageValidator;
import com.mythix.tcg.auction.domain.AuctionPaymentProof;
import com.mythix.tcg.auction.persistence.AuctionPaymentProofRepository;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.mythix.tcg.auction.application.AuctionException.*;
import static com.mythix.tcg.auction.domain.Auction.Status.*;
import static com.mythix.tcg.auction.domain.AuctionSecondChanceOffer.OfferStatus.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionSettlementService {



    private final AuctionRepository auctionRepository;
    private final AuctionCommissionService commission;
    private final ApplicationEventPublisher events;
    private final AuctionBidRepository bidRepository;
    private final AuctionSecondChanceOfferRepository secondChanceRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final CollectionRepository collectionRepository;
    private final CardRepository cardRepository;
    private final NotificationService notificationService;
    private final AuctionPaymentProofRepository paymentProofRepository;

    private final SellerProductImageValidator imageValidator = new SellerProductImageValidator();

    @Transactional
    public void closeAuction(Long auctionId, Instant now) {
        Optional<Auction> opt = auctionRepository.findByIdForUpdate(auctionId);
        if (opt.isEmpty()) return;

        Auction auction = opt.get();
        if ((auction.getStatus() != ACTIVE && auction.getStatus() != SCHEDULED) || now.isBefore(auction.getEndAt())) {
            return;
        }

        // Case 1: Unsold with zero bids
        if (auction.getBidCount() == 0) {
            releaseStockToSeller(auction);
            auction.setStatus(UNSOLD_NO_BIDS);
            auction.setUpdatedAt(now);
            auctionRepository.save(auction);
            events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

            sendNotification(auction.getSellerId(), NotificationTypes.AUCTION_ENDED_SELLER,
                "การประมูลรายการ '" + auction.getCardName() + "' สิ้นสุดลงโดยไม่มีผู้เสนอราคา สต๊อกการ์ดถูกปลดล็อกคืนสู่ระบบแล้ว",
                "/workspace/auctions/" + auction.getId());
            return;
        }

        Long winnerId = auction.getLeadingBidderId();
        if (winnerId == null || auction.getHighestBid() == null) {
            // Legacy auction with a bid count but no corresponding bidding records.
            auction.setStatus(ENDED);
            auction.setUpdatedAt(now);
            events.publishEvent(new AuctionLiveUpdates.Changed(auctionId));
            return;
        }

        // Case 2: Reserve price not met
        if (auction.getReservePrice() != null && auction.getHighestBid().compareTo(auction.getReservePrice()) < 0) {
            releaseStockToSeller(auction);
            auction.setStatus(RESERVE_NOT_MET);
            auction.setReserveMet(false);
            auction.setUpdatedAt(now);
            auctionRepository.save(auction);
            events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

            sendNotification(auction.getSellerId(), NotificationTypes.AUCTION_ENDED_SELLER,
                "การประมูลรายการ '" + auction.getCardName() + "' จบลงด้วยราคา ฿" + auction.getHighestBid()
                    + " แต่ยังไม่ถึงราคาสำรองที่กำหนด สต๊อกการ์ดถูกปลดล็อกคืนสู่ระบบแล้ว",
                "/workspace/auctions/" + auction.getId());
            return;
        }

        // Case 3: Successful auction won by highest bidder

        BigDecimal finalPrice = auction.getHighestBid();

        var policy = commission.policy();
        BigDecimal fee = policy.feeFor(finalPrice);
        BigDecimal sellerNet = finalPrice.subtract(fee);

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
        User winner = userRepository.findById(winnerId).orElse(null);
        User seller = userRepository.findById(auction.getSellerId()).orElse(null);
        if (winner == null || seller == null) {
            log.error("Winner or Seller missing for auction {}", auctionId);
            return;
        }

        // Create Auction Order
        String orderNumber = "AUC-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 900 + 100);
        Order order = Order.builder()
            .orderNumber(orderNumber)
            .buyer(winner)
            .orderType("AUCTION")
            .status("PENDING")
            .subtotalAmount(finalPrice)
            .totalAmount(finalPrice)
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
                .priceAtPurchase(finalPrice)
                .costAtPurchase(product != null && product.getCost() != null ? product.getCost() : BigDecimal.ZERO)
                .quantity(auction.getQuantity())
                .build();
        orderItemRepository.save(item);

        auction.setStatus(AWAITING_PAYMENT);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(finalPrice);
        auction.setReserveMet(true);
        auction.setOrderId(savedOrder.getId());
        auction.setPaymentDeadline(now.plus(Duration.ofHours(48)));
        auction.setPlatformFeeRate(policy.rate());
        auction.setPlatformFeeAmount(fee);
        auction.setSellerNetAmount(sellerNet);
        auction.setCostBasis(costBasis);
        auction.setStockHeld(false);
        auction.setUpdatedAt(now);
        auctionRepository.save(auction);
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

        // Notifications
        sendNotification(winnerId, NotificationTypes.AUCTION_WON,
            "ยินดีด้วย! คุณเป็นผู้ชนะการประมูลรายการ '" + auction.getCardName() + "' ในราคา ฿"
                + finalPrice.toPlainString() + " กรุณาชำระเงินภายใน 48 ชั่วโมง",
            "/auctions/me");

        sendNotification(auction.getSellerId(), NotificationTypes.AUCTION_ENDED_SELLER,
            "การประมูลรายการ '" + auction.getCardName() + "' จบลงแล้ว! ผู้ชนะคือ " + winner.getFullName()
                + " ในราคา ฿" + finalPrice.toPlainString() + " รอการชำระเงินจากผู้ซื้อ",
            "/workspace/auctions/" + auction.getId());
    }

    @Transactional(readOnly = true)
    public SettlementSummaryView getSettlementSummary(Long auctionId, Long sellerId) {
        Auction auction = auctionRepository.findByIdAndSellerId(auctionId, sellerId)
            .filter(value -> value.getOrderId() != null)
            .orElseThrow(() -> notFound("Auction settlement not found"));
        return new SettlementSummaryView(auctionId, auction.getCardName(), auction.getWinningBid(),
            auction.getPlatformFeeRate(), auction.getPlatformFeeAmount(), auction.getSellerNetAmount(),
            auction.getCostBasis(), auction.getStatus(), auction.getUpdatedAt());
    }

    public Optional<Auction> lockAuctionForOrder(Long orderId) {
        return auctionRepository.findIdByOrderId(orderId).flatMap(auctionRepository::findByIdForUpdate);
    }

    @Transactional
    public void submitPaymentProof(Long orderId, Long buyerId, MultipartFile file) {
        var image = imageValidator.validate(file);
        Auction auction = lockAuctionForOrder(orderId).orElseThrow(() -> notFound("Auction order not found"));
        if (!buyerId.equals(auction.getWinnerId()) || !orderId.equals(auction.getOrderId())) {
            throw notFound("Auction order not found");
        }
        Instant now = Instant.now();
        Order order = orderRepository.findById(orderId).orElseThrow(() -> notFound("Order not found"));
        if (!"PENDING".equals(order.getStatus()) || auction.getPaymentDeadline() == null
                || !now.isBefore(auction.getPaymentDeadline())) {
            throw conflict("This order no longer accepts payment proof");
        }
        paymentProofRepository.save(new AuctionPaymentProof(orderId, image.contentType(), image.content(), now));
    }

    public boolean hasPaymentProof(Long orderId) {
        return paymentProofRepository.existsById(orderId);
    }

    @Transactional(readOnly = true)
    public AuctionPaymentProof getPaymentProof(Long orderId, Long viewerId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> notFound("Order not found"));
        boolean participant = viewerId.equals(order.getBuyer().getId()) || orderItemRepository.findByOrderId(orderId)
            .stream().anyMatch(item -> viewerId.equals(item.getSeller().getId()));
        if (!participant || !"AUCTION".equals(order.getOrderType())) {
            throw notFound("Payment proof not found");
        }
        return paymentProofRepository.findById(orderId).orElseThrow(() -> notFound("Payment proof not found"));
    }

    @Transactional
    public void transferAuctionCardToPersonalBinder(Order order) {
        Auction auction = lockAuctionForOrder(order.getId())
            .orElseThrow(() -> conflict("Auction order is no longer current"));
        User buyer = userRepository.findByIdForUpdate(order.getBuyer().getId())
            .orElseThrow(() -> notFound("Buyer not found"));
        Card card = cardRepository.findById(auction.getCardId())
            .orElseThrow(() -> notFound("Auction card not found"));

        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            String condition = auction.getCondition();
            String language = auction.getLanguage();
            CollectionItem entry = collectionRepository.findByUserIdAndCardIdAndConditionAndLanguage(
                buyer.getId(), card.getId(), condition, language
            ).orElseGet(() -> CollectionItem.builder().user(buyer).card(card)
                .condition(condition).language(language).quantity(0).build());
            entry.setQuantity(entry.getQuantity() + item.getQuantity());
            entry.setProvenance("Auction #" + auction.getId());
            entry.setAuctionWon(true);
            entry.setGradingCompany(auction.getGradingCompany());
            entry.setGrade(auction.getGrade());
            entry.setCertNumber(auction.getCertNumber());
            entry.setImageFrontUrl("/api/auctions/" + auction.getId() + "/photos/front");
            entry.setImageBackUrl("/api/auctions/" + auction.getId() + "/photos/back");
            collectionRepository.save(entry);
        }

        commission.recognize(order.getId(), auction.getId(), auction.getPlatformFeeAmount());
        auction.setStatus(COMPLETED);
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));
        auction.setUpdatedAt(Instant.now());
    }

    @Transactional
    public SecondChanceOfferView sendSecondChanceOffer(Long auctionId, Long sellerId) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
            .orElseThrow(() -> notFound("Auction not found"));

        if (!auction.getSellerId().equals(sellerId)) {
            throw forbidden("You are not the seller of this auction");
        }

        if (auction.getStatus() != DEFAULTED) {
            throw conflict("Second-chance offers require a defaulted winner");
        }

        Instant now = Instant.now();
        if (secondChanceRepository.existsByAuctionIdAndStatusAndExpiresAtAfter(auctionId, PENDING, now)) {
            throw conflict("A second-chance offer is already pending");
        }
        AuctionBid runnerUp = bidRepository.findEligibleRunnerUp(
                auctionId, auction.getLeadingBidderId(), PageRequest.ofSize(1))
            .stream().findFirst().orElseThrow(() -> invalid("No eligible runner-up bidder found for this auction"));
        AuctionSecondChanceOffer offer = secondChanceRepository.save(AuctionSecondChanceOffer.builder()
            .auctionId(auctionId)
            .bidderId(runnerUp.getBidderId())
            .offerPrice(runnerUp.getBidAmount())
            .status(PENDING)
            .expiresAt(now.plus(Duration.ofHours(24)))
            .createdAt(now)
            .build());

        sendNotification(runnerUp.getBidderId(), NotificationTypes.AUCTION_SECOND_CHANCE,
            "คุณได้รับข้อเสนอ Second-Chance Offer สำหรับการ์ด '" + auction.getCardName()
                + "' ในราคาที่คุณเคยเคาะไว้ ฿" + runnerUp.getBidAmount().toPlainString() + " (มีเวลาตอบรับ 24 ชั่วโมง)",
            "/auctions/me");

        return new SecondChanceOfferView(
            offer.getId(),
            auction.getId(),
            auction.getCardName(),
            auction.getImageUrl(),
            auction.getQuantity(),
            offer.getOfferPrice(),
            offer.getStatus(),
            offer.getExpiresAt(),
            offer.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<SecondChanceOfferView> getPendingOffers(Long bidderId) {
        Instant now = Instant.now();
        return secondChanceRepository.findAllByBidderIdAndStatusOrderByCreatedAtDesc(bidderId, PENDING)
            .stream().filter(offer -> now.isBefore(offer.getExpiresAt()))
            .flatMap(offer -> auctionRepository.findById(offer.getAuctionId())
                .filter(auction -> auction.getStatus() == DEFAULTED)
                .map(auction -> new SecondChanceOfferView(offer.getId(), auction.getId(), auction.getCardName(),
                    auction.getImageUrl(), auction.getQuantity(), offer.getOfferPrice(), offer.getStatus(), offer.getExpiresAt(),
                    offer.getCreatedAt())).stream()).toList();
    }

    @Transactional
    public void respondSecondChanceOffer(Long offerId, Long bidderId, boolean accept) {
        Long auctionId = secondChanceRepository.findAuctionId(offerId, bidderId)
            .orElseThrow(() -> notFound("Second-chance offer not found"));
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
            .orElseThrow(() -> notFound("Auction not found"));
        AuctionSecondChanceOffer offer = secondChanceRepository.findByIdAndBidderId(offerId, bidderId)
            .orElseThrow(() -> notFound("Second-chance offer not found"));

        if (offer.getStatus() != PENDING) {
            throw conflict("Offer is already " + offer.getStatus());
        }

        Instant now = Instant.now();
        if (!now.isBefore(offer.getExpiresAt())) {
            throw conflict("Offer has expired");
        }
        if (auction.getStatus() != DEFAULTED) {
            throw conflict("This offer is no longer available");
        }

        if (!accept) {
            offer.setStatus(REJECTED);
            offer.setRespondedAt(now);
            secondChanceRepository.save(offer);
            return;
        }

        // Accept offer -> Create Order for runner-up
        offer.setStatus(ACCEPTED);
        offer.setRespondedAt(now);
        secondChanceRepository.save(offer);

        BigDecimal price = offer.getOfferPrice();
        var policy = commission.policy();
        BigDecimal fee = policy.feeFor(price);
        BigDecimal sellerNet = price.subtract(fee);

        User buyer = userRepository.findById(bidderId)
            .orElseThrow(() -> notFound("Buyer not found"));
        User seller = userRepository.findById(auction.getSellerId())
            .orElseThrow(() -> notFound("Seller not found"));

        String orderNumber = "AUC-SC-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 900 + 100);
        Order order = Order.builder()
            .orderNumber(orderNumber)
            .buyer(buyer)
            .orderType("AUCTION")
            .status("PENDING")
            .subtotalAmount(price)
            .totalAmount(price)
            .platformFee(fee)
            .sellerNetAmount(sellerNet)
            .createdAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
            .updatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
            .build();
        Order savedOrder = orderRepository.save(order);

        Product product = null;
        if (auction.getProductId() != null) {
            product = productRepository.findByIdForUpdate(auction.getProductId())
                .orElseThrow(() -> conflict("Inventory is no longer available for this offer"));
            if (product.availableStock() < auction.getQuantity())
                throw conflict("Inventory is no longer available for this offer");
            product.setReserved(product.getReserved() + auction.getQuantity());
            productRepository.save(product);
        }
        orderItemRepository.save(OrderItem.builder().order(savedOrder).product(product).seller(seller)
            .cardId(auction.getCardId()).productNameSnapshot(auction.getCardName())
            .shopNameSnapshot(seller.getFullName()).priceAtPurchase(price)
            .costAtPurchase(product != null && product.getCost() != null ? product.getCost() : BigDecimal.ZERO)
            .quantity(auction.getQuantity()).build());

        auction.setStatus(AWAITING_PAYMENT);
        auction.setWinnerId(bidderId);
        auction.setWinningBid(price);
        auction.setPlatformFeeRate(policy.rate());
        auction.setOrderId(savedOrder.getId());
        auction.setPaymentDeadline(now.plus(Duration.ofHours(48)));
        auction.setPlatformFeeAmount(fee);
        auction.setSellerNetAmount(sellerNet);
        auction.setUpdatedAt(now);
        auctionRepository.save(auction);
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

        sendNotification(auction.getSellerId(), NotificationTypes.AUCTION_ENDED_SELLER,
            "ผู้ประมูลอันดับสองยอมรับข้อเสนอ Second-Chance Offer รายการ '" + auction.getCardName() + "' เรียบร้อยแล้ว",
            "/workspace/auctions/" + auction.getId());
    }

    @Scheduled(fixedDelayString = "${auction.settlement.check-interval-ms:30000}")
    @Transactional
    public void processExpiredPayments() {
        processExpiredPaymentsAt(Instant.now());
    }

    @Transactional
    public void processExpiredPaymentsAt(Instant now) {
        List<Long> expiredAuctionIds = auctionRepository.findDueExpiredPaymentIds(List.of(AWAITING_PAYMENT, BUYOUT_COMPLETED), now, PageRequest.of(0, 50));

        for (Long auctionId : expiredAuctionIds) {
            try {
                handleDefaultedWinner(auctionId, now);
            } catch (Exception e) {
                log.error("Failed to process defaulted auction {}", auctionId, e);
            }
        }
    }

    private void handleDefaultedWinner(Long auctionId, Instant now) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null || (auction.getStatus() != AWAITING_PAYMENT && auction.getStatus() != BUYOUT_COMPLETED)
                || auction.getPaymentDeadline() == null || auction.getPaymentDeadline().isAfter(now)
                || auction.getOrderId() == null || hasPaymentProof(auction.getOrderId())
                || orderRepository.findById(auction.getOrderId()).filter(order -> "PENDING".equals(order.getStatus())).isEmpty()) {
            return;
        }

        Long winnerId = auction.getWinnerId();
        if (winnerId != null) {
            User winner = userRepository.findByIdForUpdate(winnerId).orElse(null);
            if (winner != null) {
                winner.setAuctionStrikes(winner.getAuctionStrikes() + 1);
                if (winner.getAuctionStrikes() >= 2) {
                    winner.setAuctionBannedUntil(now.plus(Duration.ofDays(30)));
                }
                userRepository.save(winner);

                sendNotification(winnerId, NotificationTypes.AUCTION_DEFAULTED_WARNING,
                    "คุณไม่ได้ชำระเงินค่าประมูลการ์ด '" + auction.getCardName()
                        + "' ภายใน 48 ชั่วโมง จึงถูกตัดสิทธิ์และได้รับ Reputation Strike 1 ครั้ง (สะสม "
                        + winner.getAuctionStrikes() + " Strikes)",
                    "/notifications");
            }
        }

        // Cancel order if present
        if (auction.getOrderId() != null) {
            orderRepository.findById(auction.getOrderId()).ifPresent(order -> {
                if ("PENDING".equalsIgnoreCase(order.getStatus())) {
                    order.setStatus("CANCELLED");
                    order.setUpdatedAt(LocalDateTime.now());
                    orderRepository.save(order);

                    // release reserved stock
                    List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
                    for (OrderItem item : items) {
                        if (item.getProduct() != null) {
                            Product p = productRepository.findByIdForUpdate(item.getProduct().getId())
                                .orElseThrow(() -> conflict("Auction inventory is no longer available"));
                            p.setReserved(Math.max(0, p.getReserved() - item.getQuantity()));
                            productRepository.save(p);
                        }
                    }
                }
            });
        }

        auction.setStatus(DEFAULTED);
        auction.setUpdatedAt(now);
        auctionRepository.save(auction);
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

        sendNotification(auction.getSellerId(), NotificationTypes.AUCTION_ENDED_SELLER,
            "ผู้ชนะการประมูลรายการ '" + auction.getCardName()
                + "' ไม่ได้ชำระเงินภายใน 48 ชั่วโมง คุณสามารถส่ง Second-Chance Offer ให้ผู้เคาะอันดับสอง หรือนำการ์ดกลับสู่สต๊อกได้",
            "/workspace/auctions/" + auction.getId());
    }

    private void releaseStockToSeller(Auction auction) {
        if (auction.isStockHeld() && auction.getProductId() != null) {
            productRepository.findByIdForUpdate(auction.getProductId()).ifPresent(p -> {
                p.setAuctionReserved(Math.max(0, p.getAuctionReserved() - auction.getQuantity()));
                productRepository.save(p);
            });
            auction.setStockHeld(false);
        }
    }

    private void sendNotification(Long userId, String type, String message, String route) {
        try {
            userRepository.findById(userId).ifPresent(user ->
                notificationService.sendAuctionNotification(user, type, "Auction Update", message, route)
            );
        } catch (Exception e) {
            log.warn("Failed to deliver notification to user {}: {}", userId, e.getMessage());
        }
    }
}

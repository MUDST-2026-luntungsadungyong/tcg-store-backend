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
import org.springframework.data.domain.PageRequest;
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
            .status("AWAITING_BUYER_CONTACT")
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

        auction.setStatus(AWAITING_BUYER_CONTACT);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(finalPrice);
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

        // Notifications
        sendNotification(winnerId, NotificationTypes.AUCTION_WON,
            "ยินดีด้วย! คุณเป็นผู้ชนะการประมูลรายการ '" + auction.getCardName() + "' ในราคา ฿"
                + finalPrice.toPlainString() + " กรุณาติดต่อร้านค้าเพื่อดำเนินการชำระเงินและจัดส่ง",
            "/orders/" + savedOrder.getId());

        sendNotification(auction.getSellerId(), NotificationTypes.AUCTION_ENDED_SELLER,
            "การประมูลรายการ '" + auction.getCardName() + "' จบลงแล้ว! ผู้ชนะคือ " + winner.getFullName()
                + " ในราคา ฿" + finalPrice.toPlainString() + " กรุณารอผู้ชนะติดต่อเพื่อดำเนินการชำระเงินและจัดส่ง",
            "/workspace/orders?orderId=" + savedOrder.getId());
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

    public void closeCancelledOrder(Auction auction, boolean noBuyerContact) {
        auction.setStatus(noBuyerContact ? DEFAULTED : CANCELLED);
        auction.setUpdatedAt(Instant.now());
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));
        if (noBuyerContact) {
            sendNotification(auction.getWinnerId(), NotificationTypes.AUCTION_DEFAULTED_WARNING,
                "ร้านค้ายกเลิกออเดอร์ประมูลเพราะยังไม่ได้รับการติดต่อจากคุณ",
                "/orders/" + auction.getOrderId());
        }
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
            .status("AWAITING_BUYER_CONTACT")
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

        auction.setStatus(AWAITING_BUYER_CONTACT);
        auction.setWinnerId(bidderId);
        auction.setWinningBid(price);
        auction.setPlatformFeeRate(policy.rate());
        auction.setOrderId(savedOrder.getId());
        auction.setPlatformFeeAmount(fee);
        auction.setSellerNetAmount(sellerNet);
        auction.setUpdatedAt(now);
        auctionRepository.save(auction);
        events.publishEvent(new AuctionLiveUpdates.Changed(auction.getId()));

        sendNotification(bidderId, NotificationTypes.AUCTION_WON,
            "คุณตอบรับข้อเสนอ Second-Chance สำหรับรายการ '" + auction.getCardName()
                + "' สำเร็จ กรุณาติดต่อร้านค้าเพื่อดำเนินการชำระเงินและจัดส่ง",
            "/orders/" + savedOrder.getId());

        sendNotification(auction.getSellerId(), NotificationTypes.AUCTION_ENDED_SELLER,
            "ผู้ประมูลอันดับสองยอมรับข้อเสนอ Second-Chance Offer รายการ '" + auction.getCardName() + "' เรียบร้อยแล้ว",
            "/workspace/orders?orderId=" + savedOrder.getId());
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

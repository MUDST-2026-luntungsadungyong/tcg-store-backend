package com.mythix.tcg.auction.application;

import com.mythix.tcg.auction.api.AuctionExtendedDtos.AuctionSummaryView;
import com.mythix.tcg.auction.api.AuctionExtendedDtos.WatchlistStatusResponse;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionPhoto;
import com.mythix.tcg.auction.domain.AuctionWatchlist;
import com.mythix.tcg.auction.persistence.AuctionPhotoRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.auction.persistence.AuctionWatchlistRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.mythix.tcg.auction.application.AuctionException.notFound;
import static com.mythix.tcg.auction.domain.AuctionPhoto.Side.BACK;
import static com.mythix.tcg.auction.domain.AuctionPhoto.Side.FRONT;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionWatchlistService {

    private final AuctionWatchlistRepository watchlistRepository;
    private final AuctionRepository auctionRepository;
    private final AuctionPhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Transactional
    public WatchlistStatusResponse toggleWatchlist(Long userId, Long auctionId) {
        auctionRepository.findByIdForUpdate(auctionId)
            .filter(auction -> auction.getStatus() != Auction.Status.DRAFT)
            .orElseThrow(() -> notFound("Auction not found"));

        Optional<AuctionWatchlist> existing = watchlistRepository.findByUserIdAndAuctionId(userId, auctionId);
        if (existing.isPresent()) {
            watchlistRepository.delete(existing.get());
            return new WatchlistStatusResponse(auctionId, false);
        } else {
            watchlistRepository.save(AuctionWatchlist.builder()
                .userId(userId)
                .auctionId(auctionId)
                .createdAt(Instant.now())
                .build());
            return new WatchlistStatusResponse(auctionId, true);
        }
    }

    @Transactional(readOnly = true)
    public List<AuctionSummaryView> getUserWatchlist(Long userId) {
        List<AuctionWatchlist> items = watchlistRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (items.isEmpty()) {
            return List.of();
        }

        List<Long> auctionIds = items.stream().map(AuctionWatchlist::getAuctionId).toList();
        List<Auction> auctions = auctionRepository.findAllById(auctionIds);

        Map<Long, Set<AuctionPhoto.Side>> photoSides = loadPhotoSides(auctionIds);
        Set<Long> sellerIds = new HashSet<>();
        for (Auction a : auctions) {
            sellerIds.add(a.getSellerId());
        }
        Map<Long, User> sellers = new HashMap<>();
        for (User u : userRepository.findAllById(sellerIds)) {
            sellers.put(u.getId(), u);
        }

        Instant now = Instant.now();
        return auctions.stream().filter(a -> a.getStatus() != Auction.Status.DRAFT).map(a -> {
            Set<AuctionPhoto.Side> sides = photoSides.getOrDefault(a.getId(), Set.of());
            String front = sides.contains(FRONT) ? "/api/auctions/" + a.getId() + "/photos/front" : null;
            String back = sides.contains(BACK) ? "/api/auctions/" + a.getId() + "/photos/back" : null;
            User seller = sellers.get(a.getSellerId());
            String sellerName = seller != null ? (seller.getFullName() != null ? seller.getFullName() : "Seller") : "Seller";
            BigDecimal currentPrice = a.getHighestBid() != null ? a.getHighestBid() : a.getStartPrice();

            return new AuctionSummaryView(
                a.getId(),
                a.getStatus(),
                a.getSellerId(),
                sellerName,
                a.getCardId(),
                a.getCardName(),
                a.getSetName(),
                a.getImageUrl(),
                a.getCondition(),
                a.getQuantity(),
                a.getLanguage(),
                a.getGradingCompany(),
                a.getGrade(),
                a.getCertNumber(),
                a.getStartPrice(),
                currentPrice,
                a.getBidCount(),
                a.getBuyItNowPrice(),
                a.isReserveMet(),
                a.getStartAt(),
                a.getEndAt(),
                now,
                true,
                front,
                back
            );
        }).toList();
    }

    @Scheduled(fixedDelayString = "${auction.watchlist.check-interval-ms:60000}")
    @Transactional
    public void processEndingReminders() {
        Instant now = Instant.now();

        // 1-hour window: 55m to 65m
        Instant window1hStart = now.plus(Duration.ofMinutes(55));
        Instant window1hEnd = now.plus(Duration.ofMinutes(65));
        for (AuctionWatchlist w : watchlistRepository.findDue1HourWatchlists(window1hStart, window1hEnd)) {
            auctionRepository.findById(w.getAuctionId()).ifPresent(auction -> {
                if (watchlistRepository.claimOneHourReminder(w.getId()) == 1) {
                    sendWatchlistReminder(w.getUserId(), auction, "เหลือเวลาอีกประมาณ 1 ชั่วโมงก่อนปิดการประมูล");
                }
            });
        }

        // 15-minute window: 10m to 16m
        Instant window15mStart = now.plus(Duration.ofMinutes(10));
        Instant window15mEnd = now.plus(Duration.ofMinutes(16));
        for (AuctionWatchlist w : watchlistRepository.findDue15MinWatchlists(window15mStart, window15mEnd)) {
            auctionRepository.findById(w.getAuctionId()).ifPresent(auction -> {
                if (watchlistRepository.claimFifteenMinuteReminder(w.getId()) == 1) {
                    sendWatchlistReminder(w.getUserId(), auction, "เหลือเวลาอีกเพียง 15 นาทีสุดท้ายก่อนปิดการประมูล!");
                }
            });
        }
    }

    private void sendWatchlistReminder(Long userId, Auction auction, String timeframeMessage) {
        try {
            userRepository.findById(userId).ifPresent(user ->
                notificationService.sendAuctionNotification(
                    user,
                    NotificationTypes.AUCTION_WATCHLIST_ENDING,
                    "รายการที่คุณติดตามใกล้จบแล้ว",
                    "รายการที่คุณติดตาม '" + auction.getCardName() + "' " + timeframeMessage,
                    "/auctions/" + auction.getId()
                )
            );
        } catch (Exception e) {
            log.warn("Failed to send watchlist notification to user {}: {}", userId, e.getMessage());
        }
    }

    private Map<Long, Set<AuctionPhoto.Side>> loadPhotoSides(List<Long> auctionIds) {
        Map<Long, Set<AuctionPhoto.Side>> sides = new HashMap<>();
        for (AuctionPhoto.Id id : photoRepository.findPhotoIds(auctionIds)) {
            sides.computeIfAbsent(id.auctionId(), unused -> EnumSet.noneOf(AuctionPhoto.Side.class)).add(id.side());
        }
        return sides;
    }
}

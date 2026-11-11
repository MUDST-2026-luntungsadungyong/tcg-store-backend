package com.mythix.tcg.auction.application;

import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.auction.api.AuctionLiveUpdates;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.persistence.AuctionBidRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import static com.mythix.tcg.auction.application.AuctionException.conflict;
import static com.mythix.tcg.auction.application.AuctionException.invalid;

@Service
@RequiredArgsConstructor
public class AdminAuctionService {
    private final AuctionRepository auctions;
    private final AuctionBidRepository bids;
    private final AuctionService auctionService;
    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;

    public record View(Long id, Long sellerId, String cardName, Auction.Status status, Instant endAt,
                       int bidCount, BigDecimal highestBid, Long leadingBidderId, Instant pausedAt,
                       Instant latestBidAt, boolean highRiskUser, boolean priceAnomaly) {}

    @Transactional(readOnly = true)
    public Page<View> list(Long adminId, int page, int size) {
        requireAdmin(adminId);
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
            Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        return auctions.findAllByStatusIn(List.of(Auction.Status.SCHEDULED, Auction.Status.ACTIVE,
            Auction.Status.PAUSED), pageable).map(this::view);
    }

    @Transactional
    public View act(Long adminId, Long id, String action, String reason) {
        User admin = requireAdmin(adminId);
        String clean = AuctionService.bounded(reason, 1000, "Reason");
        if (clean == null) throw invalid("Enter a reason");
        Auction auction = auctionService.lockedAuction(id);
        Instant now = Instant.now();
        switch (action) {
            case "pause" -> {
                if (auction.getStatus() != Auction.Status.ACTIVE || !now.isBefore(auction.getEndAt())) {
                    throw conflict("Only a live auction can be paused");
                }
                auction.setStatus(Auction.Status.PAUSED);
                auction.setPausedAt(now);
            }
            case "resume" -> {
                if (auction.getStatus() != Auction.Status.PAUSED || auction.getPausedAt() == null) {
                    throw conflict("Only a paused auction can be resumed");
                }
                auction.setEndAt(auction.getEndAt().plus(Duration.between(auction.getPausedAt(), now)));
                auction.setPausedAt(null);
                auction.setStatus(Auction.Status.ACTIVE);
            }
            case "cancel" -> {
                if (!(auction.canCancel(now) || auction.getStatus() == Auction.Status.PAUSED)) {
                    throw conflict("This auction cannot be cancelled");
                }
                auctionService.cancelLocked(auction, now);
                auction.setPausedAt(null);
            }
            default -> throw invalid("Unknown action");
        }
        auction.setUpdatedAt(now);
        auditLogs.save(AuditLog.builder().actor(admin).action("AUCTION_" + action.toUpperCase(Locale.ROOT))
            .targetType("AUCTION").targetId(id.toString()).details(clean).build());
        var recipients = new HashSet<Long>();
        recipients.add(auction.getSellerId());
        bids.findAllByAuctionIdOrderByCreatedAtDescIdDesc(id).forEach(bid -> recipients.add(bid.getBidderId()));
        String label = action.equals("pause") ? "paused" : action.equals("resume") ? "resumed" : "cancelled";
        users.findAllById(recipients).forEach(user -> notifications.sendAuctionNotification(user,
            NotificationTypes.AUCTION_CANCELLATION_DECIDED, "Auction update",
            "Auction #" + id + " was " + label + ".", "/auctions/" + id));
        events.publishEvent(new AuctionLiveUpdates.Changed(id));
        return view(auction);
    }

    private User requireAdmin(Long id) {
        if (id == null) throw new AccessDeniedException("Admin access is required");
        return users.findById(id).filter(user -> "ADMIN".equals(user.getRole()))
            .orElseThrow(() -> new AccessDeniedException("Admin access is required"));
    }

    private View view(Auction auction) {
        var recent = bids.findTop2ByAuctionIdOrderByCreatedAtDescIdDesc(auction.getId());
        var latest = recent.isEmpty() ? null : recent.get(0);
        boolean highRisk = latest != null && users.findById(latest.getBidderId())
            .map(user -> user.getAuctionStrikes() > 0 || user.isAuctionBanned()).orElse(false);
        boolean anomaly = recent.size() > 1 && !latest.getCreatedAt().isBefore(recent.get(1).getCreatedAt())
            && Duration.between(recent.get(1).getCreatedAt(), latest.getCreatedAt()).compareTo(Duration.ofMinutes(5)) <= 0
            && latest.getBidAmount().compareTo(recent.get(1).getBidAmount().multiply(BigDecimal.valueOf(2))) >= 0;
        return new View(auction.getId(), auction.getSellerId(), auction.getCardName(), auction.getStatus(),
            auction.getEndAt(), auction.getBidCount(), auction.getHighestBid(), auction.getLeadingBidderId(),
            auction.getPausedAt(), latest == null ? null : latest.getCreatedAt(), highRisk, anomaly);
    }
}

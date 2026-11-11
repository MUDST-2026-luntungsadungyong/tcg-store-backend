package com.mythix.tcg.auction.application;

import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionDefaultCase;
import com.mythix.tcg.auction.domain.AuctionDefaultDecision;
import com.mythix.tcg.auction.persistence.AuctionDefaultCaseRepository;
import com.mythix.tcg.auction.persistence.AuctionDefaultDecisionRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.user.application.AuctionUserLock;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.notification.domain.NotificationTypes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import static com.mythix.tcg.auction.application.AuctionException.conflict;
import static com.mythix.tcg.auction.application.AuctionException.invalid;
import static com.mythix.tcg.auction.application.AuctionException.notFound;

@Service
@RequiredArgsConstructor
public class AuctionDefaultCaseService {
    private final AuctionDefaultCaseRepository cases;
    private final AuctionDefaultDecisionRepository decisions;
    private final AuctionRepository auctions;
    private final UserRepository users;
    private final AuctionUserLock auctionUserLock;
    private final OrderRepository orders;
    private final NotificationService notifications;
    public record History(String action, String reason, Long adminId, Instant createdAt) {}
    public record CaseView(Long id, Long auctionId, Long orderId, String orderNumber, Long buyerId, Long sellerId, String reportReason,
                           String status, Instant createdAt, Instant decidedAt, String decisionReason, List<History> history) {}
    public record Penalty(int activeStrikes, Instant bannedUntil) {}

    public boolean report(Auction auction, String reason) {
        String clean = AuctionService.bounded(reason, 2000, "Reason");
        if (clean == null) throw invalid("Enter a reason");
        if (!cases.existsByOrderId(auction.getOrderId())) {
            cases.save(AuctionDefaultCase.builder().auctionId(auction.getId()).orderId(auction.getOrderId())
                .orderNumber(orders.findById(auction.getOrderId()).orElseThrow(() -> notFound("Order not found")).getOrderNumber())
                .buyerId(auction.getWinnerId()).sellerId(auction.getSellerId()).reportReason(clean)
                .status("PENDING").createdAt(Instant.now()).build());
            notifyParticipant(auction.getSellerId(), "Your report was submitted for review.", "/workspace/orders?orderId=" + auction.getOrderId());
            return true;
        }
        return false;
    }
    @Transactional(readOnly = true)
    public Page<CaseView> mine(Long userId, int page, int size) {
        requireUser(userId);
        return cases.findByBuyerIdOrSellerId(userId, userId, pageable(page, size)).map(this::view);
    }
    @Transactional(readOnly = true)
    public Penalty penalty(Long userId) {
        User user = requireUser(userId);
        return new Penalty(user.getAuctionStrikes(), user.getAuctionBannedUntil());
    }
    @Transactional(readOnly = true)
    public Page<CaseView> list(Long adminId, int page, int size) {
        requireAdmin(adminId);
        return cases.findAll(pageable(page, size)).map(this::view);
    }
    @Transactional
    public CaseView decide(Long adminId, Long id, String action, String reason) {
        requireAdmin(adminId);
        String clean = AuctionService.bounded(reason, 2000, "Reason");
        if (clean == null) throw invalid("Enter a reason");
        Long auctionId = cases.findAuctionId(id).orElseThrow(() -> notFound("Default case not found"));
        auctions.findByIdForUpdate(auctionId).orElseThrow(() -> notFound("Auction not found"));
        AuctionDefaultCase initial = cases.findById(id).orElseThrow(() -> notFound("Default case not found"));
        User buyer = auctionUserLock.lock(initial.getBuyerId()).orElseThrow(() -> notFound("Buyer not found"));
        AuctionDefaultCase c = cases.findByIdForUpdate(id).orElseThrow(() -> notFound("Default case not found"));
        String target = switch (action) {
            case "approve" -> "APPROVED";
            case "reject" -> "REJECTED";
            case "reverse" -> "REVERSED";
            default -> throw invalid("Unknown decision");
        };
        if (target.equals(c.getStatus())) return view(c);
        if ("reverse".equals(action) ? !"APPROVED".equals(c.getStatus()) : !"PENDING".equals(c.getStatus()))
            throw conflict("This case cannot receive that decision");
        if (buyer.getAuctionPenaltyBaselineStrikes() == null) {
            buyer.setAuctionPenaltyBaselineStrikes(buyer.getAuctionStrikes());
            buyer.setAuctionPenaltyBaselineBan(buyer.getAuctionBannedUntil());
        } else if (!Objects.equals(buyer.getAuctionBannedUntil(), buyer.getAuctionPenaltyAppliedBan())) {
            buyer.setAuctionPenaltyBaselineBan(max(buyer.getAuctionPenaltyBaselineBan(), buyer.getAuctionBannedUntil()));
        }
        Instant now = Instant.now();
        c.setStatus(target);
        c.setDecidedAt(now);
        c.setDecisionReason(clean);
        cases.saveAndFlush(c);
        decisions.save(AuctionDefaultDecision.builder().caseId(id).adminId(adminId).action(target).reason(clean).createdAt(now).build());
        List<AuctionDefaultCase> active = cases.findByBuyerIdAndStatusOrderByDecidedAtAscIdAsc(buyer.getId(), "APPROVED");
        int count = buyer.getAuctionPenaltyBaselineStrikes();
        Instant ban = buyer.getAuctionPenaltyBaselineBan();
        for (AuctionDefaultCase item : active) {
            count++;
            if (count >= 2) ban = max(ban, item.getDecidedAt().plus(Duration.ofDays(count >= 3 ? 30 : 7)));
        }
        buyer.setAuctionStrikes(count);
        buyer.setAuctionBannedUntil(ban);
        buyer.setAuctionPenaltyAppliedBan(ban);
        notifyParticipant(c.getBuyerId(), "The buyer default case was " + target.toLowerCase() + ".", "/orders/" + c.getOrderId());
        notifyParticipant(c.getSellerId(), "The buyer default case was " + target.toLowerCase() + ".", "/workspace/orders?orderId=" + c.getOrderId());
        return view(c);
    }
    private void notifyParticipant(Long userId, String message, String path) {
        User recipient = users.findById(userId).orElseThrow(() -> notFound("User not found"));
        notifications.sendAuctionNotification(recipient, NotificationTypes.AUCTION_DEFAULTED_WARNING,
            "Auction default review", message, path);
    }
    private static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    private User requireUser(Long id) {
        if (id == null) throw new AccessDeniedException("Authentication required");
        return users.findById(id).orElseThrow(() -> new AccessDeniedException("Authentication required"));
    }

    private void requireAdmin(Long id) {
        if (!"ADMIN".equals(requireUser(id).getRole())) throw new AccessDeniedException("Admin access is required");
    }

    private Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)),
            Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    private CaseView view(AuctionDefaultCase c) {
        List<History> history = decisions.findByCaseIdOrderByCreatedAtAscIdAsc(c.getId()).stream()
            .map(d -> new History(d.getAction(), d.getReason(), d.getAdminId(), d.getCreatedAt())).toList();
        return new CaseView(c.getId(), c.getAuctionId(), c.getOrderId(), c.getOrderNumber(), c.getBuyerId(), c.getSellerId(),
            c.getReportReason(), c.getStatus(), c.getCreatedAt(), c.getDecidedAt(), c.getDecisionReason(), history);
    }
}

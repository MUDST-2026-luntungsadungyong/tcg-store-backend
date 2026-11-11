package com.mythix.tcg.auction.application;

import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionDispute;
import com.mythix.tcg.auction.domain.AuctionDisputeEvidence;
import com.mythix.tcg.auction.persistence.AuctionDisputeEvidenceRepository;
import com.mythix.tcg.auction.persistence.AuctionDisputeRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.order.persistence.OrderRepository;

import com.mythix.tcg.seller.application.SellerProductImageValidator;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;

import java.util.List;
import java.util.Set;

import static com.mythix.tcg.auction.application.AuctionException.conflict;
import static com.mythix.tcg.auction.application.AuctionException.invalid;
import static com.mythix.tcg.auction.application.AuctionException.missing;

@Service
@RequiredArgsConstructor
public class AuctionDisputeService {
    private final AuctionRepository auctions;
    private final AuctionDisputeRepository disputes;
    private final AuctionDisputeEvidenceRepository evidence;
    private final OrderRepository orders;

    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final NotificationService notifications;

    public record EvidenceView(String url, Long submittedBy, Instant createdAt) {}

    public record View(Long id, Long auctionId, Long orderId, Long buyerId, Long sellerId, String reason,
                       String status, Instant createdAt, Instant decidedAt, String decisionReason,
                       List<String> evidenceUrls, List<EvidenceView> evidence) {}

    @Transactional
    public View submit(Long buyerId, Long auctionId, String reason, List<MultipartFile> files) {
        if (buyerId == null) throw new AccessDeniedException("Authentication required");
        Auction auction = auctions.findByIdForUpdate(auctionId).orElseThrow(() -> missing("Auction not found"));
        if (!buyerId.equals(auction.getWinnerId()) || auction.getOrderId() == null) throw missing("Auction not found");
        var order = orders.findById(auction.getOrderId()).orElseThrow(() -> missing("Order not found"));
        if (!"FULFILLED".equals(order.getStatus())) throw conflict("Receipt must be confirmed before opening a dispute");
        Instant received = auction.getReceivedAt();
        if (received == null) throw conflict("Verified receipt timestamp unavailable for this auction");
        if (Instant.now().isAfter(received.plus(Duration.ofDays(3)))) throw conflict("The dispute window has closed");
        if (disputes.existsByAuctionId(auctionId)) throw conflict("A dispute already exists for this auction");
        String clean = AuctionService.bounded(reason, 2000, "Reason");
        if (clean == null) throw invalid("Enter a reason");
        if (files == null || files.isEmpty() || files.size() > 4) throw invalid("Attach one to four evidence images");

        var dispute = new AuctionDispute();
        dispute.setAuctionId(auctionId);
        dispute.setOrderId(order.getId());
        dispute.setBuyerId(buyerId);
        dispute.setSellerId(auction.getSellerId());
        dispute.setReason(clean);
        dispute.setStatus("PENDING");
        dispute.setCreatedAt(Instant.now());
        disputes.save(dispute);
        for (MultipartFile file : files) {
            var image = new SellerProductImageValidator().validate(file);
            var attachment = new AuctionDisputeEvidence();
            attachment.setDisputeId(dispute.getId());
            attachment.setSubmittedBy(buyerId);
            attachment.setCreatedAt(Instant.now());
            attachment.setContentType(image.contentType());
            attachment.setContent(image.content());
            evidence.save(attachment);
        }
        notifyUser(dispute.getSellerId(), NotificationTypes.AUCTION_DISPUTE_OPENED,
            "A dispute was opened", "A dispute was opened for auction #" + auctionId + ".", dispute.getId());
        return view(dispute, false);
    }

    @Transactional
    public View addEvidence(Long userId, Long id, List<MultipartFile> files) {
        var dispute = disputes.lock(id).orElseThrow(() -> missing("Dispute not found"));
        requireParticipant(userId, dispute);
        if (!"PENDING".equals(dispute.getStatus())) throw conflict("Dispute already decided");
        if (files == null || files.isEmpty() || files.size() + evidence.findRefs(id).size() > 8) {
            throw invalid("Attach evidence within the eight image limit");
        }
        for (MultipartFile file : files) {
            var image = new SellerProductImageValidator().validate(file);
            var attachment = new AuctionDisputeEvidence();
            attachment.setDisputeId(id);
            attachment.setSubmittedBy(userId);
            attachment.setCreatedAt(Instant.now());
            attachment.setContentType(image.contentType());
            attachment.setContent(image.content());
            evidence.save(attachment);
        }
        notifyUser(userId.equals(dispute.getBuyerId()) ? dispute.getSellerId() : dispute.getBuyerId(),
            NotificationTypes.AUCTION_DISPUTE_EVIDENCE_ADDED, "New dispute evidence",
            "New evidence was added to a dispute for auction #" + dispute.getAuctionId() + ".", dispute.getId());
        return view(dispute, false);
    }
    @Transactional(readOnly = true)
    public Page<View> list(Long adminId, int page, int size) {
        requireAdmin(adminId);
        return disputes.findAll(pageable(page, size)).map(dispute -> view(dispute, true));
    }

    @Transactional(readOnly = true)
    public Page<View> mine(Long userId, int page, int size) {
        if (userId == null) throw new AccessDeniedException("Authentication required");
        return disputes.findByBuyerIdOrSellerId(userId, userId, pageable(page, size))
            .map(dispute -> view(dispute, false));
    }

    @Transactional(readOnly = true)
    public View detail(Long userId, Long id) {
        var dispute = participantDispute(userId, id);
        return view(dispute, false);
    }

    @Transactional
    public View decide(Long adminId, Long id, String decision, String reason) {
        requireAdmin(adminId);
        if (decision == null || !Set.of("FULL_REFUND", "RETURN", "DISMISS").contains(decision)) throw invalid("Invalid decision");
        String clean = AuctionService.bounded(reason, 2000, "Reason");
        if (clean == null) throw invalid("Enter a reason");
        var dispute = disputes.lock(id).orElseThrow(() -> missing("Dispute not found"));
        if (dispute.getStatus().equals(decision)) return view(dispute, true);
        if (!dispute.getStatus().equals("PENDING")) throw conflict("Dispute already decided");
        dispute.setStatus(decision);
        dispute.setDecisionReason(clean);
        dispute.setDecidedBy(adminId);
        dispute.setDecidedAt(Instant.now());
        auditLogs.save(AuditLog.builder().actor(users.findById(adminId).orElseThrow())
            .action("AUCTION_DISPUTE_DECISION").targetType("AUCTION_DISPUTE").targetId(id.toString())
            .details(decision + ": " + clean).build());
        String decisionLabel = switch (decision) {
            case "FULL_REFUND" -> "Full refund";
            case "RETURN" -> "Return";
            default -> "Dismissed";
        };
        String boundedReason = clean.length() > 650 ? clean.substring(0, 647) + "..." : clean;
        String decisionMessage = "Decision: " + decisionLabel + ". Reason: " + boundedReason;
        notifyUser(dispute.getBuyerId(), NotificationTypes.AUCTION_DISPUTE_DECIDED,
            "Auction dispute decided", decisionMessage, dispute.getId());
        notifyUser(dispute.getSellerId(), NotificationTypes.AUCTION_DISPUTE_DECIDED,
            "Auction dispute decided", decisionMessage, dispute.getId());
        return view(dispute, true);
    }

    @Transactional(readOnly = true)
    public AuctionDisputeEvidence file(Long userId, Long id, Long fileId, boolean admin) {
        if (admin) requireAdmin(userId);
        else participantDispute(userId, id);
        return evidence.findByIdAndDisputeId(fileId, id).orElseThrow(() -> missing("Evidence not found"));
    }

    private AuctionDispute participantDispute(Long userId, Long id) {
        var dispute = disputes.findById(id).orElseThrow(() -> missing("Dispute not found"));
        requireParticipant(userId, dispute);
        return dispute;
    }

    private void requireParticipant(Long userId, AuctionDispute dispute) {
        if (userId == null || !(userId.equals(dispute.getBuyerId()) || userId.equals(dispute.getSellerId()))) {
            throw missing("Dispute not found");
        }
    }

    private void notifyUser(Long id, String type, String title, String message, Long disputeId) {
        users.findById(id).ifPresent(user -> notifications.sendAuctionNotification(user,
            type, title, message, "/auction-disputes?disputeId=" + disputeId));
    }

    private void requireAdmin(Long id) {
        if (id == null || users.findById(id).filter(user -> "ADMIN".equals(user.getRole())).isEmpty()) {
            throw new AccessDeniedException("Admin access is required");
        }
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private View view(AuctionDispute dispute, boolean admin) {
        String base = admin ? "/api/admin/auction-disputes/" : "/api/auction-disputes/";
        List<EvidenceView> files = evidence.findRefs(dispute.getId()).stream().map(file ->
            new EvidenceView(base + dispute.getId() + "/evidence/" + file.getId(), file.getSubmittedBy(), file.getCreatedAt())).toList();
        return new View(dispute.getId(), dispute.getAuctionId(), dispute.getOrderId(), dispute.getBuyerId(),
            dispute.getSellerId(), dispute.getReason(), dispute.getStatus(), dispute.getCreatedAt(),
            dispute.getDecidedAt(), dispute.getDecisionReason(), files.stream().map(EvidenceView::url).toList(), files);
    }
}

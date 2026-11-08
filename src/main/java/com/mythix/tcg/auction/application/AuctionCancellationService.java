package com.mythix.tcg.auction.application;

import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.auction.api.AuctionDtos.CancellationView;
import com.mythix.tcg.auction.api.AuctionDtos.ImageView;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionCancellationEvidence;
import com.mythix.tcg.auction.domain.AuctionCancellationRequest;
import com.mythix.tcg.auction.domain.AuctionCancellationRequest.Status;
import com.mythix.tcg.auction.persistence.AuctionCancellationRepository;
import com.mythix.tcg.auction.persistence.AuctionEvidenceRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.seller.application.SellerProductImageValidator;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mythix.tcg.auction.application.AuctionException.conflict;
import static com.mythix.tcg.auction.application.AuctionException.invalid;
import static com.mythix.tcg.auction.application.AuctionException.missing;

@Service
@RequiredArgsConstructor
public class AuctionCancellationService {
    private final AuctionService auctionService;
    private final AuctionRepository auctions;
    private final AuctionCancellationRepository requests;
    private final AuctionEvidenceRepository evidence;
    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final NotificationService notifications;
    private final SellerProductImageValidator imageValidator = new SellerProductImageValidator();

    @Transactional
    public CancellationView submit(Long sellerId, Long auctionId, String reason, List<MultipartFile> files) {
        Auction auction = auctionService.lockedOwnedAuction(sellerId, auctionId);
        Instant now = Instant.now();
        if (!auction.canCancel(now) || auction.getBidCount() == 0) {
            throw conflict("Only a cancellable auction with bids needs a cancellation request");
        }
        if (requests.findFirstByAuctionIdAndStatusOrderByIdDesc(auctionId, Status.PENDING_REVIEW).isPresent()) {
            throw conflict("A cancellation request is already awaiting review");
        }

        String requestReason = requiredReason(reason);
        if (files == null || files.isEmpty() || files.size() > 4) {
            throw invalid("Attach between one and four evidence images");
        }

        var request = new AuctionCancellationRequest();
        request.setAuctionId(auctionId);
        request.setReason(requestReason);
        request.setCreatedAt(now);
        requests.save(request);
        for (MultipartFile file : files) {
            var image = imageValidator.validate(file);
            evidence.save(new AuctionCancellationEvidence(request.getId(), safeFileName(file.getOriginalFilename()),
                image.contentType(), image.content()));
        }
        User seller = seller(sellerId);
        notifications.notifyAdminsOfAuctionCancellation(auctionId, users.findByRoleOrderByCreatedAtDesc("ADMIN"));
        return response(auction, request, seller, false);
    }

    @Transactional(readOnly = true)
    public List<CancellationView> listForAdmin(Long adminId) {
        requireAdmin(adminId);
        var entries = requests.findAllByOrderByCreatedAtDescIdDesc();
        if (entries.isEmpty()) {
            return List.of();
        }

        Map<Long, Auction> auctionsById = auctions.findAllById(entries.stream()
            .map(AuctionCancellationRequest::getAuctionId).distinct().toList())
            .stream().collect(Collectors.toMap(Auction::getId, Function.identity()));
        Map<Long, User> sellers = users.findAllById(auctionsById.values().stream()
            .map(Auction::getSellerId).distinct().toList())
            .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        var filesByRequest = evidence.findFileRefs(entries.stream().map(AuctionCancellationRequest::getId).toList())
            .stream().collect(Collectors.groupingBy(AuctionEvidenceRepository.FileRef::requestId));
        return entries.stream().map(request -> {
            Auction auction = auctionsById.get(request.getAuctionId());
            return AuctionService.cancellationView(auction, request, sellers.get(auction.getSellerId()),
                filesByRequest.getOrDefault(request.getId(), List.of()), true);
        }).toList();
    }

    @Transactional(readOnly = true)
    public CancellationView detailForAdmin(Long adminId, Long requestId) {
        requireAdmin(adminId);
        var request = request(requestId);
        Auction auction = auctions.findById(request.getAuctionId()).orElseThrow(() -> missing("Auction not found"));
        return response(auction, request, seller(auction.getSellerId()), true);
    }

    @Transactional
    public CancellationView decide(Long adminId, Long requestId, boolean approve, String reason) {
        User admin = requireAdmin(adminId);

        // All request mutations serialize on the auction row, before touching the request or inventory.
        Auction auction = auctionService.lockedAuction(requests.findAuctionId(requestId)
            .orElseThrow(() -> missing("Cancellation request not found")));
        var request = request(requestId);
        Status decision = approve ? Status.APPROVED : Status.REJECTED;
        User seller = seller(auction.getSellerId());
        if (request.getStatus() == decision) {
            return response(auction, request, seller, true);
        }
        if (request.getStatus() != Status.PENDING_REVIEW) {
            throw conflict("This cancellation request has already been reviewed");
        }

        Instant now = Instant.now();
        if (approve) {
            if (!auction.canCancel(now)) {
                throw conflict("The auction is no longer cancellable. Review its latest status");
            }
            auctionService.cancelLocked(auction, now);
        } else {
            request.setDecisionReason(requiredReason(reason));
        }
        request.setStatus(decision);
        request.setReviewedBy(adminId);
        request.setReviewedAt(now);
        auditLogs.save(AuditLog.builder()
            .actor(admin)
            .action(approve ? "APPROVE_AUCTION_CANCELLATION" : "REJECT_AUCTION_CANCELLATION")
            .targetType("AUCTION_CANCELLATION")
            .targetId(requestId.toString())
            .details(approve ? "Approved auction cancellation" : request.getDecisionReason())
            .build());
        notifications.notifySellerOfAuctionCancellationDecision(seller, auction.getId(), approve);
        return response(auction, request, seller, true);
    }

    @Transactional(readOnly = true)
    public ImageView sellerEvidence(Long sellerId, Long auctionId, Long requestId, Long fileId) {
        auctionService.ownedAuction(sellerId, auctionId);
        if (!request(requestId).getAuctionId().equals(auctionId)) {
            throw missing("Cancellation evidence not found");
        }
        return readEvidence(requestId, fileId);
    }

    @Transactional(readOnly = true)
    public ImageView adminEvidence(Long adminId, Long requestId, Long fileId) {
        requireAdmin(adminId);
        return readEvidence(requestId, fileId);
    }

    private ImageView readEvidence(Long requestId, Long fileId) {
        var file = evidence.findByIdAndRequestId(fileId, requestId)
            .orElseThrow(() -> missing("Cancellation evidence not found"));
        return new ImageView(file.getContentType(), file.getContent());
    }

    private CancellationView response(Auction auction, AuctionCancellationRequest request, User seller, boolean admin) {
        return AuctionService.cancellationView(auction, request, seller, evidence.findFileRefs(List.of(request.getId())),
            admin);
    }

    private AuctionCancellationRequest request(Long id) {
        return requests.findById(id).orElseThrow(() -> missing("Cancellation request not found"));
    }

    private User seller(Long sellerId) {
        return users.findById(sellerId).orElseThrow(() -> missing("Seller not found"));
    }

    private User requireAdmin(Long adminId) {
        return users.findById(adminId).filter(user -> "ADMIN".equals(user.getRole()))
            .orElseThrow(() -> new AccessDeniedException("Admin access is required"));
    }

    private static String requiredReason(String reason) {
        String result = AuctionService.bounded(reason, 1000, "Reason");
        if (result == null) {
            throw invalid("Enter a reason");
        }
        return result;
    }

    private static String safeFileName(String originalName) {
        if (originalName == null) {
            return "Evidence image";
        }
        String normalized = originalName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isBlank()) {
            return "Evidence image";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }
}

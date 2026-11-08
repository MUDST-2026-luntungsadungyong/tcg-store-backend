package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.api.AuctionDtos.CancellationView;
import com.mythix.tcg.auction.api.AuctionDtos.RejectForm;
import com.mythix.tcg.auction.application.AuctionCancellationService;
import com.mythix.tcg.auth.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/auction-cancellation-requests")
@RequiredArgsConstructor
public class AdminAuctionCancellationController {
    private final AuctionCancellationService cancellations;

    @GetMapping
    public List<CancellationView> list(@AuthenticationPrincipal AuthenticatedUser admin) {
        return cancellations.listForAdmin(admin.userId());
    }

    @GetMapping("/{id}")
    public CancellationView detail(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable Long id) {
        return cancellations.detailForAdmin(admin.userId(), id);
    }

    @GetMapping("/{id}/evidence/{fileId}")
    public ResponseEntity<byte[]> evidence(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable Long id,
                                           @PathVariable Long fileId) {
        return SellerAuctionController.imageResponse(cancellations.adminEvidence(admin.userId(), id, fileId));
    }

    @PostMapping("/{id}/approve")
    public CancellationView approve(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable Long id) {
        return cancellations.decide(admin.userId(), id, true, null);
    }

    @PostMapping("/{id}/reject")
    public CancellationView reject(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable Long id,
                                   @RequestBody RejectForm form) {
        return cancellations.decide(admin.userId(), id, false, form.reason());
    }
}

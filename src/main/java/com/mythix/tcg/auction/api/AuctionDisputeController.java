package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.application.AuctionDisputeService;
import com.mythix.tcg.auth.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class AuctionDisputeController {
    private final AuctionDisputeService service;

    public record Decision(String decision, String reason) {}

    @PostMapping(path = "/api/auctions/{id}/disputes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AuctionDisputeService.View submit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
                                            @RequestParam String reason, @RequestPart List<MultipartFile> evidence) {
        return service.submit(userId(user), id, reason, evidence);
    }

    @GetMapping("/api/auction-disputes/me")
    public Page<AuctionDisputeService.View> mine(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.mine(userId(user), page, size);
    }

    @GetMapping("/api/auction-disputes/{id}")
    public AuctionDisputeService.View detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return service.detail(userId(user), id);
    }

    @PostMapping(path = "/api/auction-disputes/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AuctionDisputeService.View addEvidence(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id, @RequestPart List<MultipartFile> evidence) {
        return service.addEvidence(userId(user), id, evidence);
    }

    @GetMapping({"/api/admin/auction-disputes", "/api/admin/disputes"})
    public Page<AuctionDisputeService.View> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(userId(user), page, size);
    }

    @PostMapping("/api/admin/auction-disputes/{id}/decide")
    public AuctionDisputeService.View decide(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id, @RequestBody Decision form) {
        return service.decide(userId(user), id, form.decision(), form.reason());
    }

    @GetMapping({"/api/admin/auction-disputes/{id}/evidence/{fileId}", "/api/auction-disputes/{id}/evidence/{fileId}"})
    public ResponseEntity<byte[]> evidence(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
            @PathVariable Long fileId, HttpServletRequest request) {
        var file = service.file(userId(user), id, fileId, request.getRequestURI().startsWith("/api/admin/"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.getContentType()))
            .header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, no-store").body(file.getContent());
    }

    private Long userId(AuthenticatedUser user) {
        return user == null ? null : user.userId();
    }
}

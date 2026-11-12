package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.application.AdminAuctionService;
import com.mythix.tcg.auth.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auctions")
@RequiredArgsConstructor
public class AdminAuctionController {
    private final AdminAuctionService service;
    public record Reason(String reason) {}

    @GetMapping({"", "/live-feed"})
    public Page<AdminAuctionService.View> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(user == null ? null : user.userId(), page, size);
    }

    @PostMapping("/{id}/{action:pause|resume|cancel|force-cancel}")
    public AdminAuctionService.View act(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
            @PathVariable String action, @RequestBody Reason form) {
        return service.act(user == null ? null : user.userId(), id,
            action.equals("force-cancel") ? "cancel" : action, form.reason());
    }
}

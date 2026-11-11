package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.application.AuctionDefaultCaseService;
import com.mythix.tcg.auction.application.AuctionDefaultCaseService.CaseView;
import com.mythix.tcg.auction.application.AuctionDefaultCaseService.Penalty;
import com.mythix.tcg.auth.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuctionDefaultCaseController {
    private final AuctionDefaultCaseService service;

    public record DecisionForm(String reason) {}

    @GetMapping("/api/auction-default-cases/me")
    public Page<CaseView> mine(@AuthenticationPrincipal AuthenticatedUser user,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return service.mine(userId(user), page, size);
    }

    @GetMapping("/api/auction-default-cases/penalty")
    public Penalty penalty(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.penalty(userId(user));
    }

    @GetMapping("/api/admin/auction-default-cases")
    public Page<CaseView> list(@AuthenticationPrincipal AuthenticatedUser user,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return service.list(userId(user), page, size);
    }

    @PostMapping("/api/admin/auction-default-cases/{caseId}/{action:approve|reject|reverse}")
    public CaseView decide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long caseId,
                           @PathVariable String action, @RequestBody DecisionForm form) {
        return service.decide(userId(user), caseId, action, form.reason());
    }

    private Long userId(AuthenticatedUser user) {
        return user == null ? null : user.userId();
    }
}

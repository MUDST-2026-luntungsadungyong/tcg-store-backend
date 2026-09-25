package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.application.AdminSellerService;
import com.mythix.tcg.auth.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/admin/seller-applications")
@RequiredArgsConstructor
public class AdminSellerController {
    private final AdminSellerService adminSellerService;

    @GetMapping
    public List<AdminSellerApplicationResponse> pendingApplications() {
        return adminSellerService.pendingApplications();
    }

    @PatchMapping("/{applicationId}/approve")
    public AdminSellerApplicationResponse approve(@PathVariable Long applicationId,
                                                   @AuthenticationPrincipal AuthenticatedUser admin) {
        return adminSellerService.approve(applicationId, admin.userId());
    }

    @PatchMapping("/{applicationId}/reject")
    public AdminSellerApplicationResponse reject(@PathVariable Long applicationId, @AuthenticationPrincipal AuthenticatedUser admin,
        @Valid @RequestBody RejectSellerApplicationRequest request) {
        return adminSellerService.reject(applicationId, admin.userId(), request.reason());
    }
}

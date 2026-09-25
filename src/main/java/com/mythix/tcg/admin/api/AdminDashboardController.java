package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.application.AdminSellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {
    private final AdminSellerService adminSellerService;

    @GetMapping
    public AdminDashboardResponse dashboard() {
        return adminSellerService.dashboard();
    }

}

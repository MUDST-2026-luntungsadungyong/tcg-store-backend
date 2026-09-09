package com.mythix.tcg.seller.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.seller.application.StoreProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class StoreProfileController {
    private final StoreProfileService service;
    @GetMapping("/api/sellers/me/store") public StoreProfileResponse mine(@AuthenticationPrincipal AuthenticatedUser user) { return service.mine(user.userId()); }
    @PutMapping("/api/sellers/me/store") public StoreProfileResponse update(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody StoreProfileRequest request) { return service.update(user.userId(), request); }
    @GetMapping("/api/stores/{sellerId}") public StoreProfileResponse publicStore(@PathVariable Long sellerId) { return service.publicStore(sellerId); }
}

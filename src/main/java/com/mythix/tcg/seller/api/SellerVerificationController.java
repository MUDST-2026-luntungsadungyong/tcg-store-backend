package com.mythix.tcg.seller.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.seller.application.SellerVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sellers")
@RequiredArgsConstructor
public class SellerVerificationController {
    private final SellerVerificationService sellerVerificationService;

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.CREATED)
    public SellerVerificationResponse verify(@AuthenticationPrincipal AuthenticatedUser user,
                                             @Valid @RequestBody SellerVerificationRequest request) {
        return sellerVerificationService.verify(user.userId(), request);
    }
}

package com.mythix.tcg.order.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.order.api.CheckoutDto.CheckoutRequest;
import com.mythix.tcg.order.api.CheckoutDto.CheckoutResponse;
import com.mythix.tcg.order.application.CheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class CheckoutController {
    private final CheckoutService checkoutService;

    @PostMapping
    public ResponseEntity<CheckoutResponse> checkout(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody(required = false) CheckoutRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(checkoutService.checkout(user.userId(), idempotencyKey));
    }
}

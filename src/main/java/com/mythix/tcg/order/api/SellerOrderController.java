package com.mythix.tcg.order.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.order.api.SellerOrderDto.*;
import com.mythix.tcg.order.application.SellerOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seller/orders")
@RequiredArgsConstructor
@Validated
public class SellerOrderController {

    private final SellerOrderService sellerOrderService;

    @GetMapping
    public ResponseEntity<Page<SellerOrderResponse>> getSellerOrders(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String status) {

        Pageable pageable = PageRequest.of(page, size);
        Page<SellerOrderResponse> response = sellerOrderService.getSellerOrders(user.userId(), status, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<SellerOrderResponse> getSellerOrderById(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long orderId) {

        SellerOrderResponse response = sellerOrderService.getSellerOrderById(user.userId(), orderId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{orderId}/status")
    public ResponseEntity<SellerOrderResponse> updateOrderStatus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {

        SellerOrderResponse response = sellerOrderService.updateOrderStatus(user.userId(), orderId, request.status());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/profit")
    public ResponseEntity<SellerProfitSummaryResponse> getProfitSummary(
            @AuthenticationPrincipal AuthenticatedUser user) {

        SellerProfitSummaryResponse response = sellerOrderService.getProfitSummary(user.userId());
        return ResponseEntity.ok(response);
    }
}

package com.mythix.tcg.order.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderResponse;
import com.mythix.tcg.order.application.BuyerOrderService;
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

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class BuyerOrderController {

    private final BuyerOrderService buyerOrderService;

    @GetMapping({"/me", "/my-orders"})
    public ResponseEntity<?> getBuyerOrders(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) @Max(100) Integer size) {

        if (page != null) {
            int pageSize = (size != null) ? size : 20;
            Pageable pageable = PageRequest.of(page, pageSize);
            Page<BuyerOrderResponse> response = buyerOrderService.getBuyerOrdersPaged(user.userId(), status, pageable);
            return ResponseEntity.ok(response);
        }

        List<BuyerOrderResponse> response = buyerOrderService.getBuyerOrders(user.userId(), status);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<BuyerOrderResponse> getBuyerOrderById(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long orderId) {

        BuyerOrderResponse response = buyerOrderService.getBuyerOrderById(user.userId(), orderId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{orderId}/status")
    public ResponseEntity<BuyerOrderResponse> updateOrderStatus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long orderId,
            @RequestBody BuyerOrderDto.UpdateBuyerOrderStatusRequest request) {
        
        BuyerOrderResponse response = buyerOrderService.updateOrderStatus(user.userId(), orderId, request.status(), request.note());
        return ResponseEntity.ok(response);
    }
}

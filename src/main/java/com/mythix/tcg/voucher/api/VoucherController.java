package com.mythix.tcg.voucher.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.voucher.api.VoucherDto.*;
import com.mythix.tcg.voucher.application.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vouchers")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;

    @GetMapping
    public ResponseEntity<List<VoucherResponse>> getAvailableVouchers(
        @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Long userId = user != null ? user.userId() : null;
        return ResponseEntity.ok(voucherService.getAllAvailableVouchers(userId));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<UserVoucherResponse> claimVoucher(
        @PathVariable("id") Long voucherId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(voucherService.claimVoucher(user.userId(), voucherId));
    }

    @GetMapping("/my-vouchers")
    public ResponseEntity<List<UserVoucherResponse>> getMyVouchers(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(name = "status", required = false) String status
    ) {
        return ResponseEntity.ok(voucherService.getUserVouchers(user.userId(), status));
    }

    @PostMapping("/validate")
    public ResponseEntity<VoucherValidateResponse> validateVoucher(
        @RequestBody VoucherValidateRequest request,
        @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Long userId = user != null ? user.userId() : null;
        return ResponseEntity.ok(voucherService.validateAndCalculate(request.code(), userId, request.subtotal()));
    }
}

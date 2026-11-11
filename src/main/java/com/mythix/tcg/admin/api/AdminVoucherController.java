package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.application.AdminVoucherService;
import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.voucher.api.VoucherDto.AdminVoucherRequest;
import com.mythix.tcg.voucher.api.VoucherDto.AdminVoucherResponse;
import com.mythix.tcg.voucher.api.VoucherDto.AdminVoucherStatusRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/vouchers")
@RequiredArgsConstructor
public class AdminVoucherController {

    private final AdminVoucherService adminVoucherService;

    @GetMapping
    public List<AdminVoucherResponse> list(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String discountType
    ) {
        return adminVoucherService.listVouchers(search, status, discountType);
    }

    @GetMapping("/{id}")
    public AdminVoucherResponse getOne(@PathVariable Long id) {
        return adminVoucherService.getVoucher(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminVoucherResponse create(
        @AuthenticationPrincipal AuthenticatedUser admin,
        @RequestBody AdminVoucherRequest request
    ) {
        return adminVoucherService.createVoucher(admin != null ? admin.userId() : null, request);
    }

    @PutMapping("/{id}")
    public AdminVoucherResponse update(
        @PathVariable Long id,
        @AuthenticationPrincipal AuthenticatedUser admin,
        @RequestBody AdminVoucherRequest request
    ) {
        return adminVoucherService.updateVoucher(admin != null ? admin.userId() : null, id, request);
    }

    @PatchMapping("/{id}/status")
    public AdminVoucherResponse toggleStatus(
        @PathVariable Long id,
        @AuthenticationPrincipal AuthenticatedUser admin,
        @RequestBody AdminVoucherStatusRequest request
    ) {
        return adminVoucherService.toggleVoucherStatus(
            admin != null ? admin.userId() : null,
            id,
            request != null ? request.isActive() : null
        );
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        @PathVariable Long id,
        @AuthenticationPrincipal AuthenticatedUser admin
    ) {
        adminVoucherService.deleteVoucher(admin != null ? admin.userId() : null, id);
    }
}

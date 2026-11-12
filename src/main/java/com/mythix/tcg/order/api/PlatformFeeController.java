package com.mythix.tcg.order.api;

import com.mythix.tcg.order.application.PlatformFeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/platform/fee-policy")
@RequiredArgsConstructor
public class PlatformFeeController {
    private final PlatformFeeService platformFeeService;

    @GetMapping
    public PlatformFeePolicyResponse getPolicy() {
        PlatformFeeService.Policy policy = platformFeeService.currentPolicy();
        return new PlatformFeePolicyResponse(policy.rate(), policy.minimumFee());
    }

    public record PlatformFeePolicyResponse(BigDecimal rate, BigDecimal minimumFee) {}
}

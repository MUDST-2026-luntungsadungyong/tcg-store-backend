package com.mythix.tcg.voucher.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

public class VoucherDto {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VoucherResponse(
        Long id,
        String code,
        String title,
        String description,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minSpend,
        BigDecimal maxDiscountAmount,
        Integer totalQuota,
        Integer usedQuota,
        Integer remainingQuota,
        Integer quotaPercentage,
        String startTime,
        String endTime,
        Boolean isActive,
        Boolean isClaimed,
        Boolean canClaim
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserVoucherResponse(
        Long id,
        Long voucherId,
        String code,
        String title,
        String description,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minSpend,
        BigDecimal maxDiscountAmount,
        String status,
        String claimedAt,
        String usedAt,
        String endTime
    ) {}

    public record VoucherValidateRequest(
        String code,
        BigDecimal subtotal
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VoucherValidateResponse(
        boolean valid,
        String code,
        String discountType,
        BigDecimal discountValue,
        BigDecimal discountAmount,
        BigDecimal subtotal,
        BigDecimal finalTotal,
        String message
    ) {}
}

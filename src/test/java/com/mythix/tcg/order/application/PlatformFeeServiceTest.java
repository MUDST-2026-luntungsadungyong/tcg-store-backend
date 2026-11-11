package com.mythix.tcg.order.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformFeeServiceTest {
    private final PlatformFeeService.Policy policy = new PlatformFeeService.Policy(new BigDecimal("0.0500"), new BigDecimal("20.00"));

    @Test
    void appliesTheMinimumFeeAndNeverExceedsTheOrderSubtotal() {
        assertThat(policy.feeFor(new BigDecimal("30.00"))).isEqualByComparingTo("20.00");
        assertThat(policy.feeFor(new BigDecimal("10.00"))).isEqualByComparingTo("10.00");
    }

    @Test
    void calculatesPercentageFeeWithTwoDecimalRounding() {
        assertThat(policy.feeFor(new BigDecimal("1250.00"))).isEqualByComparingTo("62.50");
    }
}

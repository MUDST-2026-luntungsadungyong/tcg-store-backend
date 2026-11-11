package com.mythix.tcg.order.application;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Central policy for the platform fee charged to direct marketplace orders. */
@Service
@RequiredArgsConstructor
public class PlatformFeeService {
    private static final int POLICY_ID = 1;
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.0500");
    private static final BigDecimal DEFAULT_MINIMUM_FEE = new BigDecimal("20.00");

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Policy currentPolicy() {
        PlatformFeePolicy row = entityManager.find(PlatformFeePolicy.class, POLICY_ID);
        if (row == null) return new Policy(DEFAULT_RATE, DEFAULT_MINIMUM_FEE);
        return new Policy(row.rate, row.minimumFee);
    }

    public record Policy(BigDecimal rate, BigDecimal minimumFee) {
        public BigDecimal feeFor(BigDecimal subtotal) {
            if (subtotal == null || subtotal.signum() <= 0) return BigDecimal.ZERO;
            return subtotal.multiply(rate)
                .setScale(2, RoundingMode.HALF_UP)
                .max(minimumFee)
                .min(subtotal);
        }
    }
}

@Entity
@Table(name = "platform_fee_policy")
class PlatformFeePolicy {
    @Id
    Integer id;
    BigDecimal rate;
    BigDecimal minimumFee;
}

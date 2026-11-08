package com.mythix.tcg.auction.application;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class AuctionCommissionService {
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Policy policy() {
        AuctionCommissionPolicy stored = entityManager.find(AuctionCommissionPolicy.class, 1);
        return stored == null ? new Policy(new BigDecimal("0.0500"), new BigDecimal("20.00"))
            : new Policy(stored.rate, stored.minimumFee);
    }

    @Transactional
    public Policy update(BigDecimal rate, BigDecimal minimumFee) {
        AuctionCommissionPolicy row = new AuctionCommissionPolicy();
        row.id = 1;
        row.rate = rate;
        row.minimumFee = minimumFee;
        entityManager.merge(row);
        return new Policy(rate, minimumFee);
    }

    public void recognize(Long orderId, Long auctionId, BigDecimal amount) {
        entityManager.persist(new AuctionFeeRevenue(orderId, auctionId, amount, java.time.Instant.now()));
    }

    public record Policy(BigDecimal rate, BigDecimal minimumFee) {
        public BigDecimal feeFor(BigDecimal amount) {
            return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP).max(minimumFee).min(amount);
        }
    }
}

@Entity
@Table(name = "auction_commission_policy")
class AuctionCommissionPolicy {
    @Id
    Integer id;

    BigDecimal rate;
    BigDecimal minimumFee;
}

@Entity
@Table(name = "auction_fee_revenue")
class AuctionFeeRevenue {
    @Id
    private Long orderId;
    private Long auctionId;
    private BigDecimal amount;
    private java.time.Instant recognizedAt;

    protected AuctionFeeRevenue() {}

    AuctionFeeRevenue(Long orderId, Long auctionId, BigDecimal amount, java.time.Instant recognizedAt) {
        this.orderId = orderId;
        this.auctionId = auctionId;
        this.amount = amount;
        this.recognizedAt = recognizedAt;
    }
}

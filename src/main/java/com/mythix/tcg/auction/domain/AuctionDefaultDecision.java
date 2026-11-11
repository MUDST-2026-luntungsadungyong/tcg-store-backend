package com.mythix.tcg.auction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "auction_default_decisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionDefaultDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "case_id", nullable = false)
    private Long caseId;
    @Column(name = "admin_id", nullable = false)
    private Long adminId;
    @Column(nullable = false, length = 20)
    private String action;
    @Column(nullable = false, length = 2000)
    private String reason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

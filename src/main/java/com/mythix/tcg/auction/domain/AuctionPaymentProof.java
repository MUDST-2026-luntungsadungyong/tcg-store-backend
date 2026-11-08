package com.mythix.tcg.auction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "auction_payment_proofs")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AuctionPaymentProof {
    @Id
    private Long orderId;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(nullable = false)
    private Instant submittedAt;
}

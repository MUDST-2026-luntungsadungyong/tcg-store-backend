package com.mythix.tcg.auction.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "auction_cancellation_evidence")
@Getter
@NoArgsConstructor
public class AuctionCancellationEvidence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long requestId;
    @Column(nullable = false, length = 200)
    private String fileName;
    @Column(nullable = false, length = 30)
    private String contentType;
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    public AuctionCancellationEvidence(Long requestId, String fileName, String contentType, byte[] content) {
        this.requestId = requestId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.content = content;
    }
}

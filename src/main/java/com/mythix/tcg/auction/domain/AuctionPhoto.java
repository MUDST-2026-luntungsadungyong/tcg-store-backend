package com.mythix.tcg.auction.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Entity
@Table(name = "auction_photos")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AuctionPhoto {
    public enum Side { FRONT, BACK }

    @Embeddable
    public record Id(Long auctionId, @Enumerated(EnumType.STRING) @Column(length = 5) Side side)
        implements Serializable {}

    @EmbeddedId
    private Id id;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;
}

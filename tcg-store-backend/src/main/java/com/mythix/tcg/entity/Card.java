package com.mythix.tcg.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Card {

    @Id
    @Column(length = 100)
    private String id;

    @Column(nullable = false)
    private String name;

    private String supertype;

    private String subtypes;

    private String hp;

    private String types;

    private String rarity;

    @Column(name = "set_name")
    private String setName;

    @Column(name = "card_number")
    private String cardNumber;

    private String artist;

    @Column(name = "image_small", length = 500)
    private String imageSmall;

    @Column(name = "image_large", length = 500)
    private String imageLarge;

    @Column(name = "market_price", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal marketPrice = BigDecimal.ZERO;
}

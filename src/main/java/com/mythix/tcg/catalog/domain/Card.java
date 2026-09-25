package com.mythix.tcg.catalog.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "global_cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Card {

    @Id
    @Column(length = 100)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 50)
    private String supertype;

    @Column(length = 100)
    private String subtypes;

    @Column(length = 10)
    private String hp;

    @Column(length = 50)
    private String types;

    @Column(length = 100)
    private String rarity;

    @Column(name = "set_name", nullable = false, length = 255)
    private String setName;

    @Column(name = "card_number", nullable = false, length = 50)
    private String cardNumber;

    @Column(length = 255)
    private String artist;

    @Column(name = "image_small", nullable = false, length = 500)
    private String imageSmall;

    @Column(name = "image_large", nullable = false, length = 500)
    private String imageLarge;

    @Column(name = "market_price", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal marketPrice = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_by_admin_id")
    private Long createdByAdminId;

    @Column(name = "updated_by_admin_id")
    private Long updatedByAdminId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

package com.mythix.tcg.product.domain;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.user.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "local_cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "global_card_id", nullable = false)
    private Card card;

    @Column(name = "custom_image_url", nullable = false, length = 500)
    private String customImageUrl;

    @Column(name = "`condition`", nullable = false, length = 50)
    private String condition;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String language = "EN";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal cost = BigDecimal.ZERO;

    @Column(name = "on_hand", nullable = false)
    @Builder.Default
    private Integer onHand = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer reserved = 0;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "SHOW";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    private void applyRevision5Defaults() {
        if (customImageUrl == null || customImageUrl.isBlank()) {
            customImageUrl = card == null ? "" : firstNonBlank(card.getImageLarge(), card.getImageSmall());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

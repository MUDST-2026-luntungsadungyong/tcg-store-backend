package com.mythix.tcg.notification.domain;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.user.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_subscriptions", uniqueConstraints = @UniqueConstraint(
    name = "uq_stock_subscription_buyer_card", columnNames = {"buyer_id", "global_card_id"}))
@Getter
@Setter
@NoArgsConstructor
public class StockSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "global_card_id", nullable = false)
    private Card card;

    @Column(name = "subscribed_at", nullable = false, updatable = false)
    private LocalDateTime subscribedAt;

    @PrePersist
    void setSubscribedAt() {
        if (subscribedAt == null) subscribedAt = LocalDateTime.now();
    }
}

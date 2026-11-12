package com.mythix.tcg.seller.domain;

import com.mythix.tcg.product.domain.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "seller_stock_alert_state")
@Getter
@Setter
@NoArgsConstructor
public class SellerStockAlertState {
    @Id
    @Column(name = "product_id")
    private Long productId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "last_alert_level", nullable = false, length = 20)
    private String lastAlertLevel;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

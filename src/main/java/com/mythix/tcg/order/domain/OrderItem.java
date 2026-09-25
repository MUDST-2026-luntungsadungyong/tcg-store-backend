package com.mythix.tcg.order.domain;

import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_card_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @Column(name = "global_card_id", nullable = false, length = 100)
    private String cardId;

    @Column(name = "card_name_snapshot", nullable = false, length = 255)
    private String productNameSnapshot;

    @Column(name = "shop_name_snapshot", nullable = false, length = 255)
    @Builder.Default
    private String shopNameSnapshot = "";

    @Column(name = "price_at_purchase", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAtPurchase;

    @Column(name = "cost_at_purchase", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal costAtPurchase = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer quantity;
}

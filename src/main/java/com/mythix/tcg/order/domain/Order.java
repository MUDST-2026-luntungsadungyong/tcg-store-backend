package com.mythix.tcg.order.domain;

import com.mythix.tcg.user.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders", uniqueConstraints = {
    @UniqueConstraint(name = "uq_orders_order_number", columnNames = {"order_number"}),
    @UniqueConstraint(name = "uq_orders_idempotency_key", columnNames = {"idempotency_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, length = 50)
    private String orderNumber;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    @Builder.Default
    private String idempotencyKey = generateIdempotencyKey();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "platform_fee", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal platformFee = BigDecimal.ZERO;

    @Column(name = "seller_net_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal sellerNetAmount = BigDecimal.ZERO;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private static String generateIdempotencyKey() {
        return "server-" + UUID.randomUUID();
    }
}

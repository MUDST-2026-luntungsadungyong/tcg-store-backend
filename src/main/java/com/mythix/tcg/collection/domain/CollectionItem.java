package com.mythix.tcg.collection.domain;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.user.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "collection_items", uniqueConstraints = {
        @UniqueConstraint(name = "uq_collection_user_card", columnNames = {
                "user_id", "global_card_id", "condition", "language"
        })
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CollectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "global_card_id", nullable = false)
    private Card card;

    @Column(name = "`condition`", nullable = false, length = 50)
    private String condition;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String language = "EN";

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(length = 100)
    private String provenance;

    @Column(name = "is_auction_won", nullable = false)
    @Builder.Default
    private boolean auctionWon = false;

    @Column(name = "grading_company", length = 10)
    private String gradingCompany;

    @Column(length = 30)
    private String grade;

    @Column(name = "cert_number", length = 100)
    private String certNumber;

    @Column(name = "image_front_url", length = 500)
    private String imageFrontUrl;

    @Column(name = "image_back_url", length = 500)
    private String imageBackUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

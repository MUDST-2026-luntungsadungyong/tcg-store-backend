package com.mythix.tcg.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "card_search_names", uniqueConstraints = @UniqueConstraint(
    name = "uq_card_search_name", columnNames = {"card_id", "language", "search_name"}))
public class CardSearchName {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_id", nullable = false, length = 100)
    private String cardId;

    @Column(nullable = false, length = 2)
    private String language;

    @Column(nullable = false)
    private String name;

    @Column(name = "search_name", nullable = false)
    private String searchName;

    @Column(nullable = false)
    private String source;
}

package com.mythix.tcg.catalog.persistence;

import com.mythix.tcg.catalog.domain.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CardRepository extends JpaRepository<Card, String> {
    Page<Card> findByNameContainingIgnoreCase(String name, Pageable pageable);
    Page<Card> findByRarity(String rarity, Pageable pageable);
}

package com.mythix.tcg.catalog.persistence;

import com.mythix.tcg.catalog.domain.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CardRepository extends JpaRepository<Card, String>, JpaSpecificationExecutor<Card> {
    Page<Card> findByRarity(String rarity, Pageable pageable);

    @Query("SELECT DISTINCT c.name FROM Card c")
    List<String> findDistinctNames();
}

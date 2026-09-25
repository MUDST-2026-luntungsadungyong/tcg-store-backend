package com.mythix.tcg.collection.persistence;

import com.mythix.tcg.collection.domain.CollectionItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollectionRepository extends JpaRepository<CollectionItem, Long> {

    @EntityGraph(attributePaths = {"user", "card"})
    List<CollectionItem> findByUserIdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = {"user", "card"})
    Page<CollectionItem> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<CollectionItem> findByIdAndUserId(Long id, Long userId);

    @Query("select ci from CollectionItem ci where ci.user.id = :userId and ci.card.id = :cardId "
        + "and ci.condition = :condition and ci.language = :language")
    Optional<CollectionItem> findByUserIdAndCardIdAndConditionAndLanguage(
            @Param("userId") Long userId,
            @Param("cardId") String cardId,
            @Param("condition") String condition,
            @Param("language") String language
    );

    @Query("""
            select ci.user.id as userId,
                   sum(ci.quantity) as rareCardCount,
                   count(distinct ci.card.id) as distinctRareCardCount
            from CollectionItem ci
            join ci.card card
            where lower(card.rarity) like '%rare%'
               or lower(card.rarity) = 'promo'
            group by ci.user.id
            order by sum(ci.quantity) desc,
                     count(distinct ci.card.id) desc,
                     ci.user.id asc
            """)
    List<RareCollectionAggregate> findRareCollectionAggregates(Pageable pageable);

    @Query("""
            select ci.user.id as userId,
                   sum(ci.quantity) as rareCardCount,
                   count(distinct ci.card.id) as distinctRareCardCount
            from CollectionItem ci
            join ci.card card
            where ci.user.id = :userId
              and (lower(card.rarity) like '%rare%' or lower(card.rarity) = 'promo')
            group by ci.user.id
            """)
    Optional<RareCollectionAggregate> findRareCollectionAggregate(@Param("userId") Long userId);

    @Query("""
            select ci.user.id as userId,
                   ci.card.id as cardId,
                   ci.card.name as cardName,
                   ci.card.imageLarge as imageLarge,
                   ci.card.imageSmall as imageSmall,
                   ci.card.rarity as rarity,
                   ci.quantity as quantity
            from CollectionItem ci
            join ci.card card
            where ci.user.id in :userIds
              and (lower(card.rarity) like '%rare%' or lower(card.rarity) = 'promo')
            order by ci.user.id asc, ci.quantity desc, ci.createdAt desc, ci.card.id asc
            """)
    List<RareCollectionHighlight> findRareCollectionHighlights(@Param("userIds") Collection<Long> userIds);

    interface RareCollectionAggregate {
        Long getUserId();

        Long getRareCardCount();

        Long getDistinctRareCardCount();
    }

    interface RareCollectionHighlight {
        Long getUserId();

        String getCardId();

        String getCardName();

        String getImageLarge();

        String getImageSmall();

        String getRarity();

        Integer getQuantity();
    }
}

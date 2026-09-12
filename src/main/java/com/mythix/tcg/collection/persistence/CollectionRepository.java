package com.mythix.tcg.collection.persistence;

import com.mythix.tcg.collection.domain.CollectionItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollectionRepository extends JpaRepository<CollectionItem, Long> {

    List<CollectionItem> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<CollectionItem> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<CollectionItem> findByIdAndUserId(Long id, Long userId);

    Optional<CollectionItem> findByUserIdAndCardId(Long userId, String cardId);
}

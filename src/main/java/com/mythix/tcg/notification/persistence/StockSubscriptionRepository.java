package com.mythix.tcg.notification.persistence;

import com.mythix.tcg.notification.domain.StockSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockSubscriptionRepository extends JpaRepository<StockSubscription, Long> {
    Optional<StockSubscription> findByBuyer_IdAndCard_Id(Long buyerId, String cardId);
    long deleteByBuyer_IdAndCard_Id(Long buyerId, String cardId);

    @Query("select s from StockSubscription s join fetch s.buyer join fetch s.card order by s.id")
    List<StockSubscription> findAllWithBuyerAndCard();
}

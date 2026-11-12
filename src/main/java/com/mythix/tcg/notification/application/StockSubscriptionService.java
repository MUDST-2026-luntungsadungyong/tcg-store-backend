package com.mythix.tcg.notification.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.notification.api.StockSubscriptionResponse;
import com.mythix.tcg.notification.domain.StockSubscription;
import com.mythix.tcg.notification.persistence.StockSubscriptionRepository;
import com.mythix.tcg.seller.persistence.SellerProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class StockSubscriptionService {
    private final StockSubscriptionRepository subscriptions;
    private final CardRepository cards;
    private final SellerProductRepository products;
    private final UserRepository users;

    @Value("${app.back-in-stock-notification.enabled:false}")
    private boolean enabled;

    @Transactional(readOnly = true)
    public StockSubscriptionResponse get(Long buyerId, String cardId) {
        requireEnabled();
        requireCard(cardId);
        return StockSubscriptionResponse.from(cardId,
            subscriptions.findByBuyer_IdAndCard_Id(buyerId, cardId).orElse(null));
    }

    @Transactional
    public StockSubscriptionResponse subscribe(Long buyerId, String cardId) {
        requireEnabled();
        Card card = cards.findByIdForUpdate(cardId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
        // Serialize duplicate PUTs for the same card across pods before the unique-key insert.
        var existing = subscriptions.findByBuyer_IdAndCard_Id(buyerId, cardId);
        if (existing.isPresent()) return StockSubscriptionResponse.from(cardId, existing.get());
        if (products.existsPurchasableByGlobalCardId(cardId)) throw new StockAlreadyAvailableException();
        User buyer = users.findById(buyerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        StockSubscription subscription = new StockSubscription();
        subscription.setBuyer(buyer);
        subscription.setCard(card);
        return StockSubscriptionResponse.from(cardId, subscriptions.saveAndFlush(subscription));
    }

    @Transactional
    public void unsubscribe(Long buyerId, String cardId) {
        requireEnabled();
        requireCard(cardId);
        subscriptions.deleteByBuyer_IdAndCard_Id(buyerId, cardId);
    }

    private Card requireCard(String cardId) {
        return cards.findById(cardId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
    }

    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}

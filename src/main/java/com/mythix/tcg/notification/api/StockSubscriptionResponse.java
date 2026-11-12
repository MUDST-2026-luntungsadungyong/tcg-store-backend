package com.mythix.tcg.notification.api;

import com.mythix.tcg.notification.domain.StockSubscription;

import java.time.LocalDateTime;

public record StockSubscriptionResponse(String globalCardId, boolean subscribed, LocalDateTime subscribedAt) {
    public static StockSubscriptionResponse from(String cardId, StockSubscription subscription) {
        return new StockSubscriptionResponse(cardId, subscription != null,
            subscription == null ? null : subscription.getSubscribedAt());
    }
}

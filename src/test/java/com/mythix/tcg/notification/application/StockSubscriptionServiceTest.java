package com.mythix.tcg.notification.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.notification.domain.StockSubscription;
import com.mythix.tcg.notification.persistence.StockSubscriptionRepository;
import com.mythix.tcg.seller.persistence.SellerProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StockSubscriptionServiceTest {
    private final StockSubscriptionRepository subscriptions = mock(StockSubscriptionRepository.class);
    private final CardRepository cards = mock(CardRepository.class);
    private final SellerProductRepository products = mock(SellerProductRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private StockSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new StockSubscriptionService(subscriptions, cards, products, users);
        ReflectionTestUtils.setField(service, "enabled", true);
        when(cards.findById("card-1")).thenReturn(Optional.of(Card.builder().id("card-1").build()));
        when(cards.findByIdForUpdate("card-1")).thenReturn(Optional.of(Card.builder().id("card-1").build()));
    }

    @Test
    void getReturnsFalseWhenBuyerHasNotSubscribed() {
        when(subscriptions.findByBuyer_IdAndCard_Id(7L, "card-1")).thenReturn(Optional.empty());

        var result = service.get(7L, "card-1");

        assertEquals("card-1", result.globalCardId());
        assertFalse(result.subscribed());
        assertNull(result.subscribedAt());
    }

    @Test
    void putIsIdempotentAndKeepsOriginalSubscription() {
        StockSubscription existing = new StockSubscription();
        existing.setSubscribedAt(java.time.LocalDateTime.parse("2026-01-02T03:04:05"));
        when(products.existsPurchasableByGlobalCardId("card-1")).thenReturn(false);
        when(subscriptions.findByBuyer_IdAndCard_Id(7L, "card-1"))
            .thenReturn(Optional.of(existing));

        var result = service.subscribe(7L, "card-1");

        assertTrue(result.subscribed());
        assertEquals(existing.getSubscribedAt(), result.subscribedAt());
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void repeatedPutRemainsIdempotentAfterStockBecomesAvailable() {
        StockSubscription existing = new StockSubscription();
        existing.setSubscribedAt(java.time.LocalDateTime.parse("2026-01-02T03:04:05"));
        when(subscriptions.findByBuyer_IdAndCard_Id(7L, "card-1"))
            .thenReturn(Optional.of(existing));

        var result = service.subscribe(7L, "card-1");

        assertTrue(result.subscribed());
        assertEquals(existing.getSubscribedAt(), result.subscribedAt());
        verify(products, never()).existsPurchasableByGlobalCardId("card-1");
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void putRejectsACurrentlyPurchasableCard() {
        when(products.existsPurchasableByGlobalCardId("card-1")).thenReturn(true);

        assertThrows(StockAlreadyAvailableException.class, () -> service.subscribe(7L, "card-1"));
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void putCreatesSubscriptionAndDeleteIsIdempotent() {
        when(products.existsPurchasableByGlobalCardId("card-1")).thenReturn(false);
        when(subscriptions.findByBuyer_IdAndCard_Id(7L, "card-1")).thenReturn(Optional.empty());
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        when(subscriptions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.subscribe(7L, "card-1");
        service.unsubscribe(7L, "card-1");
        service.unsubscribe(7L, "card-1");

        assertTrue(result.subscribed());
        verify(subscriptions, times(1)).saveAndFlush(any());
        verify(subscriptions, times(2)).deleteByBuyer_IdAndCard_Id(7L, "card-1");
    }

    @Test
    void disabledFeatureIsHidden() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(7L, "card-1"))
            .getStatusCode().value());
    }
}

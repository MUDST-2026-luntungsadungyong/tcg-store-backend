package com.mythix.tcg.notification.application;

import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.seller.api.StockForecastResponse;
import com.mythix.tcg.seller.domain.SellerStockAlertState;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.seller.persistence.SellerProductRepository;
import com.mythix.tcg.seller.application.StockForecastService;
import com.mythix.tcg.seller.persistence.SellerStockAlertStateRepository;
import com.mythix.tcg.notification.persistence.StockSubscriptionRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockNotificationWorkerTest {
    private final StockSubscriptionRepository subscriptions = mock(StockSubscriptionRepository.class);
    private final SellerProductRepository products = mock(SellerProductRepository.class);
    private final SellerStockAlertStateRepository states = mock(SellerStockAlertStateRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final StockForecastService forecasts = mock(StockForecastService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final StockNotificationWorker worker = new StockNotificationWorker(
        subscriptions, products, states, users, notifications, forecasts, entityManager);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(worker, "sellerAlertsEnabled", true);
        ReflectionTestUtils.setField(worker, "sellerAlertEmailEnabled", true);
        Query lockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn("MAIN");
        doNothing().when(entityManager).persist(any(SellerStockAlertState.class));
        when(products.findSellerIdsWithVisibleListings()).thenReturn(List.of(9L));
        when(users.findById(9L)).thenReturn(Optional.of(User.builder().id(9L).build()));
        when(products.findById(12L)).thenReturn(Optional.of(Product.builder().id(12L).build()));
        when(states.findById(12L)).thenReturn(Optional.empty());
        when(states.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(states.findAllByProduct_Seller_Id(9L)).thenReturn(List.of());
    }

    @Test
    void lowIsWebOnlyAndPersistsAlertState() {
        givenForecast("LOW");

        worker.run();

        verify(notifications).sendStockNotification(any(User.class), eq(NotificationTypes.SELLER_STOCK_LOW),
            anyString(), anyString(), contains("stock-forecast?search="), eq(true));
        verify(entityManager).persist(argThat((SellerStockAlertState state) -> state.getLastAlertLevel().equals("LOW")
            && state.getProductId().equals(12L) && state.getProduct() != null));
    }

    @Test
    void criticalUsesEmailAndOutOfStockUsesListingsDeepLink() {
        givenForecast("CRITICAL");
        worker.run();
        verify(notifications).sendStockNotification(any(User.class), eq(NotificationTypes.SELLER_STOCK_CRITICAL),
            anyString(), anyString(), anyString(), eq(true));

        reset(notifications, states);
        when(states.findById(12L)).thenReturn(Optional.empty());
        when(states.findAllByProduct_Seller_Id(9L)).thenReturn(List.of());
        givenForecast("OUT_OF_STOCK", "OUT_OF_STOCK");
        worker.run();
        verify(notifications).sendStockNotification(any(User.class), eq(NotificationTypes.SELLER_OUT_OF_STOCK),
            anyString(), anyString(), contains("/workspace/listings?search="), eq(true));
    }

    @Test
    void criticalCanAlertAgainAfterSilentDowngradeToLow() {
        SellerStockAlertState state = new SellerStockAlertState();
        state.setProductId(12L);
        state.setLastAlertLevel("CRITICAL");
        SellerStockAlertState downgraded = new SellerStockAlertState();
        downgraded.setProductId(12L);
        downgraded.setLastAlertLevel("LOW");
        when(states.findById(12L)).thenReturn(Optional.of(state), Optional.of(downgraded));
        givenForecast("LOW");
        worker.run();
        verify(notifications, never()).sendStockNotification(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(states).save(argThat(saved -> saved.getLastAlertLevel().equals("LOW")));

        reset(notifications);
        givenForecast("CRITICAL");
        worker.run();
        verify(notifications).sendStockNotification(any(User.class), eq(NotificationTypes.SELLER_STOCK_CRITICAL),
            anyString(), anyString(), anyString(), eq(true));
    }

    @Test
    void sameSeverityDoesNotRepeatAndSafeResetsTheCycle() {
        SellerStockAlertState critical = new SellerStockAlertState();
        critical.setProductId(12L);
        critical.setLastAlertLevel("CRITICAL");
        when(states.findById(12L)).thenReturn(Optional.of(critical));
        givenForecast("CRITICAL");

        worker.run();

        verify(notifications, never()).sendStockNotification(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());

        reset(notifications);
        givenForecast("SAFE");
        worker.run();
        verify(states).deleteById(12L);
    }

    private void givenForecast(String urgency) {
        givenForecast(urgency, "SHOW");
    }

    private void givenForecast(String urgency, String listingStatus) {
        var item = new StockForecastResponse.Item("12", "card-12", "Charizard GX", "Pokemon", "Set", "Rare",
            "NM", "EN", "", listingStatus, BigDecimal.ONE, 0, 0, 0,
            urgency.equals("OUT_OF_STOCK") ? 0 : 2, 1, 1, 1, 1,
            new BigDecimal("0.20"), 2, LocalDate.now().plusDays(2), 2, 4, urgency);
        when(forecasts.getForecast(9L, 30, "WEIGHTED", 7, 3, 30, true, null, null))
            .thenReturn(new StockForecastResponse(30, "WEIGHTED", 7, 3, 30, false, LocalDate.now(),
                new StockForecastResponse.Summary(1, 0, 0, 0, 1, 0), List.of(item)));
    }
}

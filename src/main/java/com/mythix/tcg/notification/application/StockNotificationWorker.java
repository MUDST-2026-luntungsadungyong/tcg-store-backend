package com.mythix.tcg.notification.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.notification.domain.StockSubscription;
import com.mythix.tcg.notification.persistence.StockSubscriptionRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.seller.api.StockForecastResponse;
import com.mythix.tcg.seller.application.StockForecastService;
import com.mythix.tcg.seller.domain.SellerStockAlertState;
import com.mythix.tcg.seller.persistence.SellerProductRepository;
import com.mythix.tcg.seller.persistence.SellerStockAlertStateRepository;
import com.mythix.tcg.user.persistence.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockNotificationWorker {
    private final StockSubscriptionRepository subscriptions;
    private final SellerProductRepository products;
    private final SellerStockAlertStateRepository alertStates;
    private final UserRepository users;
    private final NotificationService notifications;
    private final StockForecastService forecasts;
    private final EntityManager entityManager;

    @Value("${app.back-in-stock-notification.enabled:false}")
    private boolean backInStockEnabled;
    @Value("${app.seller-stock-alert.enabled:false}")
    private boolean sellerAlertsEnabled;
    @Value("${app.seller-stock-alert.email-enabled:false}")
    private boolean sellerAlertEmailEnabled;

    @Scheduled(fixedDelayString = "${app.stock-notification.poll-ms:60000}")
    @Transactional
    public void run() {
        if (!backInStockEnabled && !sellerAlertsEnabled) return;
        // A transaction-held DB row lock serializes workers across all application pods.
        entityManager.createNativeQuery("select lock_key from stock_notification_worker_lock where lock_key = 'MAIN' for update")
            .getSingleResult();
        if (backInStockEnabled) notifyAvailableCards();
        if (sellerAlertsEnabled) notifySellerForecastTransitions();
    }

    private void notifyAvailableCards() {
        for (StockSubscription subscription : subscriptions.findAllWithBuyerAndCard()) {
            Card card = subscription.getCard();
            if (!products.existsPurchasableByGlobalCardId(card.getId())) continue;
            notifications.sendStockNotification(subscription.getBuyer(), NotificationTypes.STOCK_AVAILABLE,
                "A card on your list is back in stock",
                card.getName() + " is available to purchase now.",
                "/cards/" + UriUtils.encodePathSegment(card.getId(), StandardCharsets.UTF_8), true);
            subscriptions.delete(subscription);
        }
    }

    private void notifySellerForecastTransitions() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Set<Long> sellerIds = new LinkedHashSet<>(products.findSellerIdsWithVisibleListings());
        sellerIds.addAll(alertStates.findDistinctSellerIdsWithState());
        for (Long sellerId : sellerIds) {
            StockForecastResponse forecast;
            try {
                forecast = forecasts.getForecast(sellerId, 30, "WEIGHTED", 7, 3, 30,
                    true, null, null);
            } catch (RuntimeException error) {
                log.warn("Skipping stock forecast alerts for seller {}: {}", sellerId, error.getMessage());
                continue;
            }
            Map<Long, StockForecastResponse.Item> items = forecast.items().stream()
                .filter(item -> "SHOW".equalsIgnoreCase(item.status()) || "OUT_OF_STOCK".equalsIgnoreCase(item.status()))
                .collect(Collectors.toMap(item -> Long.valueOf(item.productId()), Function.identity()));

            for (StockForecastResponse.Item item : items.values()) {
                if (item.urgencyStatus().equals("SAFE") || item.urgencyStatus().equals("NO_RECENT_SALES")) {
                    alertStates.deleteById(Long.valueOf(item.productId()));
                    continue;
                }
                if (!List.of("LOW", "CRITICAL", "OUT_OF_STOCK").contains(item.urgencyStatus())) continue;
                Long productId = Long.valueOf(item.productId());
                int level = alertLevel(item.urgencyStatus());
                SellerStockAlertState state = alertStates.findById(productId).orElse(null);
                int previousLevel = state == null ? 0 : alertLevel(state.getLastAlertLevel());
                if (level > previousLevel) {
                    String type = notificationType(item.urgencyStatus());
                    String path = sellerActionPath(item.name(), item.urgencyStatus());
                    String title = sellerTitle(item.urgencyStatus());
                    String message = item.urgencyStatus().equals("OUT_OF_STOCK")
                        ? item.name() + " is out of stock. Restock this listing to make it purchasable."
                        : item.name() + " is " + item.urgencyStatus().toLowerCase() + " for stock. "
                            + item.availableStock() + " units are available; estimated stockout: "
                            + (item.daysUntilStockout() == null ? "unknown" : item.daysUntilStockout() + " days") + ".";
                    users.findById(sellerId).ifPresent(seller -> notifications.sendStockNotification(seller,
                        type, title, message, path, sellerAlertEmailEnabled));
                    if (state == null) {
                        state = new SellerStockAlertState();
                        Product product = products.findById(productId).orElse(null);
                        if (product == null) continue;
                        state.setProduct(product);
                        state.setProductId(productId);
                        state.setLastAlertLevel(item.urgencyStatus());
                        state.setUpdatedAt(now);
                        // @MapsId states must be persisted, not merged as detached entities.
                        entityManager.persist(state);
                        continue;
                    }
                    state.setLastAlertLevel(item.urgencyStatus());
                    state.setUpdatedAt(now);
                    alertStates.save(state);
                } else if (level < previousLevel) {
                    // A downgrade does not alert, but makes a later escalation a new transition.
                    state.setLastAlertLevel(item.urgencyStatus());
                    state.setUpdatedAt(now);
                    alertStates.save(state);
                }
            }
            // Listings no longer visible or with no recent sales recover and reset their alert cycle.
            for (SellerStockAlertState state : alertStates.findAllByProduct_Seller_Id(sellerId)) {
                if (!items.containsKey(state.getProductId())) {
                    // Forecast omits hidden listings; they are outside the alert cycle.
                    alertStates.delete(state);
                }
            }
        }
    }

    private int alertLevel(String status) {
        if (status == null) return 0;
        return switch (status) {
            case "LOW" -> 1;
            case "CRITICAL" -> 2;
            case "OUT_OF_STOCK" -> 3;
            default -> 0;
        };
    }

    private String notificationType(String status) {
        return switch (status) {
            case "LOW" -> NotificationTypes.SELLER_STOCK_LOW;
            case "CRITICAL" -> NotificationTypes.SELLER_STOCK_CRITICAL;
            default -> NotificationTypes.SELLER_OUT_OF_STOCK;
        };
    }

    private String sellerTitle(String status) {
        return switch (status) {
            case "LOW" -> "Stock level is low";
            case "CRITICAL" -> "Restock needed soon";
            default -> "Listing is out of stock";
        };
    }

    private String sellerActionPath(String name, String status) {
        String query = UriUtils.encodeQueryParam(name == null ? "" : name, StandardCharsets.UTF_8);
        return status.equals("OUT_OF_STOCK")
            ? "/workspace/listings?search=" + query
            : "/workspace/stock-forecast?search=" + query;
    }
}

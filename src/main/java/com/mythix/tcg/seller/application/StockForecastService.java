package com.mythix.tcg.seller.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.seller.api.StockForecastResponse;
import com.mythix.tcg.seller.api.StockForecastResponse.Item;
import com.mythix.tcg.seller.api.StockForecastResponse.Summary;
import com.mythix.tcg.seller.persistence.SellerOrderItemRepository;
import com.mythix.tcg.seller.persistence.SellerProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class StockForecastService {

    private static final Set<Integer> ALLOWED_WINDOWS = Set.of(7, 14, 30, 60, 90);
    private static final Set<String> ALLOWED_METHODS = Set.of("WEIGHTED", "SIMPLE");
    private static final Set<String> ALLOWED_URGENCIES = Set.of(
        "ALL", "OUT_OF_STOCK", "CRITICAL", "LOW", "SAFE", "NO_RECENT_SALES"
    );
    private static final String NO_REVISION_5_STATUS = "__NO_REVISION_5_STATUS__";
    private static final Map<String, Integer> URGENCY_SORT_ORDER = Map.of(
        "OUT_OF_STOCK", 0,
        "CRITICAL", 1,
        "LOW", 2,
        "SAFE", 3,
        "NO_RECENT_SALES", 4
    );

    private final SellerProductRepository productRepository;
    private final SellerOrderItemRepository orderItemRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public StockForecastResponse getForecast(
        Long sellerId,
        int windowDays,
        String method,
        int leadTimeDays,
        int safetyDays,
        int targetCoverDays,
        boolean includeHidden,
        String search,
        String urgency
    ) {
        requireSeller(sellerId);
        validateWindowDays(windowDays);
        String normalizedMethod = normalizeMethod(method);
        validateNonNegativeRange("leadTimeDays", leadTimeDays, 0, 180, "Lead time must be between 0 and 180 days");
        validateNonNegativeRange("safetyDays", safetyDays, 0, 90, "Safety days must be between 0 and 90 days");
        validateNonNegativeRange("targetCoverDays", targetCoverDays, 1, 365, "Target cover days must be between 1 and 365 days");
        String normalizedUrgency = normalizeUrgency(urgency);

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        List<Product> products = productRepository
            .findAllBySeller_IdAndStatusNotOrderByUpdatedAtDescIdDesc(sellerId, NO_REVISION_5_STATUS)
            .stream()
            .filter(product -> includeHidden || "SHOW".equalsIgnoreCase(product.getStatus()))
            .toList();

        Map<Long, Long> sold7d = salesMap(sellerId, now.minusDays(7));
        Map<Long, Long> sold14d = salesMap(sellerId, now.minusDays(14));
        Map<Long, Long> sold30d = salesMap(sellerId, now.minusDays(30));
        Map<Long, Long> soldWindow = windowDays == 7 ? sold7d
            : windowDays == 14 ? sold14d
            : windowDays == 30 ? sold30d
            : salesMap(sellerId, now.minusDays(windowDays));

        List<Item> allItems = products.stream()
            .map(product -> buildForecastItem(
                product,
                today,
                windowDays,
                normalizedMethod,
                leadTimeDays,
                safetyDays,
                targetCoverDays,
                sold7d.getOrDefault(product.getId(), 0L),
                sold14d.getOrDefault(product.getId(), 0L),
                sold30d.getOrDefault(product.getId(), 0L),
                soldWindow.getOrDefault(product.getId(), 0L)
            ))
            .sorted(Comparator
                .comparingInt((Item item) -> URGENCY_SORT_ORDER.getOrDefault(item.urgencyStatus(), 99))
                .thenComparing(item -> item.daysUntilStockout() == null ? Integer.MAX_VALUE : item.daysUntilStockout())
                .thenComparing(Item::dailySalesRate, Comparator.reverseOrder())
                .thenComparing(Item::name, String.CASE_INSENSITIVE_ORDER))
            .toList();

        Summary summary = buildSummary(allItems);

        String normalizedSearch = trimToNull(search);
        List<Item> filteredItems = allItems.stream()
            .filter(item -> matchesSearch(item, normalizedSearch))
            .filter(item -> "ALL".equals(normalizedUrgency) || item.urgencyStatus().equals(normalizedUrgency))
            .toList();

        return new StockForecastResponse(
            windowDays,
            normalizedMethod,
            leadTimeDays,
            safetyDays,
            targetCoverDays,
            includeHidden,
            today,
            summary,
            filteredItems
        );
    }

    private Item buildForecastItem(
        Product product,
        LocalDate today,
        int windowDays,
        String method,
        int leadTimeDays,
        int safetyDays,
        int targetCoverDays,
        long sold7,
        long sold14,
        long sold30,
        long soldWindow
    ) {
        Card card = product.getCard();
        int onHand = product.getOnHand() == null ? 0 : Math.max(0, product.getOnHand());
        int reserved = product.getReserved() == null ? 0 : Math.max(0, product.getReserved());
        int auctionReserved = Math.max(0, product.getAuctionReserved());
        int availableStock = product.availableStock();

        BigDecimal dailyRate = calculateDailyRate(method, windowDays, sold7, sold14, soldWindow);
        boolean hasSales = dailyRate.compareTo(BigDecimal.ZERO) > 0;

        Integer daysUntilStockout;
        LocalDate estimatedStockoutDate;
        String urgencyStatus;

        if (availableStock == 0) {
            daysUntilStockout = 0;
            estimatedStockoutDate = today;
            urgencyStatus = "OUT_OF_STOCK";
        } else if (!hasSales) {
            daysUntilStockout = null;
            estimatedStockoutDate = null;
            urgencyStatus = "NO_RECENT_SALES";
        } else {
            daysUntilStockout = BigDecimal.valueOf(availableStock)
                .divide(dailyRate, 0, RoundingMode.FLOOR)
                .intValue();
            estimatedStockoutDate = today.plusDays(daysUntilStockout);
            if (daysUntilStockout <= leadTimeDays) {
                urgencyStatus = "CRITICAL";
            } else if (daysUntilStockout <= leadTimeDays + safetyDays) {
                urgencyStatus = "LOW";
            } else {
                urgencyStatus = "SAFE";
            }
        }

        int reorderPoint = dailyRate
            .multiply(BigDecimal.valueOf((long) leadTimeDays + safetyDays))
            .setScale(0, RoundingMode.CEILING)
            .intValue();

        int targetUnits = dailyRate
            .multiply(BigDecimal.valueOf(targetCoverDays))
            .setScale(0, RoundingMode.CEILING)
            .intValue();
        int suggestedRestockQty = Math.max(0, targetUnits - availableStock);

        String image = firstNonBlank(
            product.getCustomImageUrl(),
            card != null ? card.getImageLarge() : null,
            card != null ? card.getImageSmall() : null
        );

        return new Item(
            String.valueOf(product.getId()),
            card != null ? card.getId() : null,
            card != null ? card.getName() : "Unnamed Card",
            canonicalGame(card != null ? card.getSupertype() : null),
            card != null ? card.getSetName() : null,
            card != null ? card.getRarity() : null,
            product.getCondition(),
            product.getLanguage(),
            image == null ? "" : image,
            product.getStatus(),
            product.getPrice() == null ? BigDecimal.ZERO : product.getPrice(),
            onHand,
            reserved,
            auctionReserved,
            availableStock,
            soldWindow,
            sold7,
            sold14,
            sold30,
            dailyRate.setScale(2, RoundingMode.HALF_UP),
            daysUntilStockout,
            estimatedStockoutDate,
            reorderPoint,
            suggestedRestockQty,
            urgencyStatus
        );
    }

    private BigDecimal calculateDailyRate(String method, int windowDays, long sold7, long sold14, long soldWindow) {
        if ("SIMPLE".equals(method) || windowDays == 7) {
            return BigDecimal.valueOf(soldWindow)
                .divide(BigDecimal.valueOf(windowDays), 4, RoundingMode.HALF_UP);
        }

        BigDecimal rate7 = BigDecimal.valueOf(sold7).divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);
        BigDecimal rate14 = BigDecimal.valueOf(sold14).divide(BigDecimal.valueOf(14), 4, RoundingMode.HALF_UP);

        if (windowDays == 14) {
            return rate7.multiply(new BigDecimal("0.60"))
                .add(rate14.multiply(new BigDecimal("0.40")))
                .setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal rateWindow = BigDecimal.valueOf(soldWindow)
            .divide(BigDecimal.valueOf(windowDays), 4, RoundingMode.HALF_UP);

        return rate7.multiply(new BigDecimal("0.50"))
            .add(rate14.multiply(new BigDecimal("0.30")))
            .add(rateWindow.multiply(new BigDecimal("0.20")))
            .setScale(4, RoundingMode.HALF_UP);
    }

    private Summary buildSummary(List<Item> items) {
        int outOfStock = 0;
        int critical = 0;
        int low = 0;
        int safe = 0;
        int noSales = 0;

        for (Item item : items) {
            switch (item.urgencyStatus()) {
                case "OUT_OF_STOCK" -> outOfStock++;
                case "CRITICAL" -> critical++;
                case "LOW" -> low++;
                case "SAFE" -> safe++;
                default -> noSales++;
            }
        }

        return new Summary(items.size(), outOfStock, critical, low, safe, noSales);
    }

    private Map<Long, Long> salesMap(Long sellerId, LocalDateTime since) {
        Map<Long, Long> totals = new HashMap<>();
        for (SellerOrderItemRepository.ProductSalesTotal row : orderItemRepository.sumDirectUnitsSoldSince(sellerId, since)) {
            if (row.getProductId() != null && row.getUnitsSold() != null) {
                totals.put(row.getProductId(), row.getUnitsSold());
            }
        }
        return totals;
    }

    private User requireSeller(Long sellerId) {
        User seller = userRepository.findById(sellerId).orElseThrow(SellerRequiredException::new);
        if (!"APPROVED".equalsIgnoreCase(seller.getSellerStatus())) {
            throw new SellerRequiredException();
        }
        return seller;
    }

    private void validateWindowDays(int windowDays) {
        if (!ALLOWED_WINDOWS.contains(windowDays)) {
            throw new SellerProductValidationException("windowDays", "Window days must be one of 7, 14, 30, 60, or 90");
        }
    }

    private void validateNonNegativeRange(String field, int value, int min, int max, String message) {
        if (value < min || value > max) {
            throw new SellerProductValidationException(field, message);
        }
    }

    private String normalizeMethod(String method) {
        String normalized = method == null ? "WEIGHTED" : method.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(normalized)) {
            throw new SellerProductValidationException("method", "Forecast method must be WEIGHTED or SIMPLE");
        }
        return normalized;
    }

    private String normalizeUrgency(String urgency) {
        String value = trimToNull(urgency);
        if (value == null) {
            return "ALL";
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!ALLOWED_URGENCIES.contains(normalized)) {
            throw new SellerProductValidationException("urgency", "Invalid urgency filter");
        }
        return normalized;
    }

    private boolean matchesSearch(Item item, String search) {
        if (search == null) {
            return true;
        }
        String needle = search.toLowerCase(Locale.ROOT);
        return Stream.of(item.name(), item.set(), item.rarity(), item.condition(), item.language(), item.game())
            .filter(value -> value != null)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains(needle));
    }

    private String canonicalGame(String game) {
        String value = game == null ? "" : game.trim();
        if (value.equalsIgnoreCase("Pokemon") || value.equalsIgnoreCase("Pokémon")
            || value.equalsIgnoreCase("Trainer") || value.equalsIgnoreCase("Energy")) {
            return "Pokemon";
        }
        return value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

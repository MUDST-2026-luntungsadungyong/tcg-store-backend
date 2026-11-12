package com.mythix.tcg.seller.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record StockForecastResponse(
    int windowDays,
    String method,
    int leadTimeDays,
    int safetyDays,
    int targetCoverDays,
    boolean includeHidden,
    LocalDate generatedAt,
    Summary summary,
    List<Item> items
) {
    public record Summary(
        int totalSkus,
        int outOfStockCount,
        int criticalCount,
        int lowCount,
        int safeCount,
        int noRecentSalesCount
    ) {
    }

    public record Item(
        String productId,
        String cardId,
        String name,
        String game,
        String set,
        String rarity,
        String condition,
        String language,
        String image,
        String status,
        BigDecimal price,
        int onHand,
        int reserved,
        int auctionReserved,
        int availableStock,
        long soldInWindow,
        long sold7Days,
        long sold14Days,
        long sold30Days,
        BigDecimal dailySalesRate,
        Integer daysUntilStockout,
        LocalDate estimatedStockoutDate,
        int reorderPoint,
        int suggestedRestockQty,
        String urgencyStatus
    ) {
    }
}

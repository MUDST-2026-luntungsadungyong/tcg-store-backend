package com.mythix.tcg.admin.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class WeeklyReportDto {
    private WeeklyReportDto() {}

    public record CardPerformance(String cardId, String name, long unitsSold,
                                  BigDecimal revenue, BigDecimal grossProfit) {}
    public record StagnantListing(long productId, long sellerId, String cardId, String name,
                                  long onHand, BigDecimal inventoryValue, LocalDateTime listedAt) {}
    public record Report(LocalDate weekEndDate, LocalDateTime periodStart, LocalDateTime periodEnd,
                         LocalDateTime generatedAt, long stockAdded, long stockRemoved, long unitsSold,
                         long inventoryUnits, BigDecimal totalInventoryValue, BigDecimal inventoryCost,
                         BigDecimal revenue, BigDecimal grossProfit, BigDecimal totalFeesCollected,
                         List<CardPerformance> topByRevenue, List<CardPerformance> topByProfit,
                         long stagnantListings, long stagnantUnits, BigDecimal stagnantValue,
                         List<StagnantListing> stagnantInventory) {}
    public record HistoryItem(LocalDate weekEndDate, LocalDateTime generatedAt,
                              BigDecimal totalInventoryValue, BigDecimal totalFeesCollected) {}
    public record History(List<HistoryItem> content, int number, int size, long totalElements,
                          int totalPages, LocalDateTime trackingSince) {}
}

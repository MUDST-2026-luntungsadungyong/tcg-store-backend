package com.mythix.tcg.admin.api;

import java.math.BigDecimal;
import java.util.List;

public record AdminDashboardResponse(
        long totalUsers,
        long totalAdmins,
        long regularBuyers,
        long pendingSellerApplications,
        long approvedSellers,
        long totalCards,
        long listedCards,
        long totalOrders,
        BigDecimal totalOrderAmount,
        List<DailyTrend> trends) {

    public AdminDashboardResponse(long totalUsers, long totalAdmins, long regularBuyers, long pendingSellerApplications,
                                  long approvedSellers, long totalCards, long listedCards, long totalOrders,
                                  BigDecimal totalOrderAmount) {
        this(totalUsers, totalAdmins, regularBuyers, pendingSellerApplications, approvedSellers, totalCards, listedCards, totalOrders, totalOrderAmount, List.of());
    }
}

package com.mythix.tcg.admin.api;

import java.math.BigDecimal;

public record AdminDashboardResponse(long totalUsers, long totalAdmins, long regularBuyers, long pendingSellerApplications,
                                     long approvedSellers, long totalCards, long totalOrders,
                                     BigDecimal totalOrderAmount) {}

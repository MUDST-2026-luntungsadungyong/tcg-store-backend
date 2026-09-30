package com.mythix.tcg.admin.api;

import java.math.BigDecimal;

public record DailyTrend(String date, BigDecimal gmv, long orders) {}

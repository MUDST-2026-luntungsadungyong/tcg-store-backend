package com.mythix.tcg.admin.application;

import com.mythix.tcg.admin.api.WeeklyReportDto.Report;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class WeeklyReportCsv {
    private WeeklyReportCsv() {}

    public static byte[] export(Report report) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        row(csv, "section", "metric", "card_id", "card_name", "product_id", "seller_id", "quantity", "amount_thb", "gross_profit_thb");
        row(csv, "period", "start_utc", "", report.periodStart(), "", "", "", "", "");
        row(csv, "period", "end_utc_exclusive", "", report.periodEnd(), "", "", "", "", "");
        row(csv, "period", "generated_at_utc", "", report.generatedAt(), "", "", "", "", "");
        row(csv, "stock", "added", "", "", "", "", report.stockAdded(), "", "");
        row(csv, "stock", "removed_including_adjustments", "", "", "", "", report.stockRemoved(), "", "");
        row(csv, "stock", "fulfilled_units", "", "", "", "", report.unitsSold(), "", "");
        row(csv, "stock", "closing_retail_value", "", "", "", "", report.inventoryUnits(), report.totalInventoryValue(), "");
        row(csv, "stock", "closing_cost_value", "", "", "", "", "", report.inventoryCost(), "");
        row(csv, "finance", "fulfilled_revenue_and_gross_profit_before_fees", "", "", "", "", "", report.revenue(), report.grossProfit());
        row(csv, "finance", "recorded_platform_fees_on_fulfilled_orders", "", "", "", "", "", report.totalFeesCollected(), "");
        row(csv, "stagnant_summary", "listings", "", "", "", "", report.stagnantListings(), "", "");
        row(csv, "stagnant_summary", "units_and_retail_value", "", "", "", "", report.stagnantUnits(), report.stagnantValue(), "");
        for (var card : report.topByRevenue()) row(csv, "top_revenue", "", card.cardId(), card.name(), "", "", card.unitsSold(), card.revenue(), card.grossProfit());
        for (var card : report.topByProfit()) row(csv, "top_profit", "", card.cardId(), card.name(), "", "", card.unitsSold(), card.revenue(), card.grossProfit());
        for (var item : report.stagnantInventory()) row(csv, "stagnant", "retail_value", item.cardId(), item.name(), item.productId(), item.sellerId(), item.onHand(), item.inventoryValue(), "");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder csv, Object... cells) {
        csv.append(Arrays.stream(cells).map(WeeklyReportCsv::cell).collect(Collectors.joining(","))).append("\r\n");
    }

    static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        // Spreadsheet formula injection can start after spaces or control characters.
        if (value instanceof String) {
            String stripped = text.stripLeading();
            if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) text = "'" + text;
            if (!text.isEmpty() && (text.charAt(0) == '\t' || text.charAt(0) == '\r' || text.charAt(0) == '\n')) text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}

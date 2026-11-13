package com.mythix.tcg.admin.application;

import com.mythix.tcg.admin.api.WeeklyReportDto.Report;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class WeeklyReportCsv {
    private static final String AVAILABLE = "AVAILABLE";
    private static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    private static final String HISTORICAL_STOCK_NOTE =
        "Historical stock was not recorded for this period; this value is unavailable, not zero.";

    private WeeklyReportCsv() {}

    public static byte[] export(Report report) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        row(csv, "section", "metric", "card_id", "card_name", "product_id", "seller_id", "quantity", "amount_thb", "gross_profit_thb", "availability", "availability_note");
        row(csv, "period", "start_utc", "", report.periodStart(), "", "", "", "", "", AVAILABLE, "");
        row(csv, "period", "end_utc_exclusive", "", report.periodEnd(), "", "", "", "", "", AVAILABLE, "");
        row(csv, "period", "generated_at_utc", "", report.generatedAt(), "", "", "", "", "", AVAILABLE, "");
        row(csv, "period", "report_type", "", report.reportType(), "", "", "", "", "", AVAILABLE, "");
        row(csv, "legend", "zero_vs_unavailable", "", "", "", "", "", "", "", AVAILABLE,
            "0 is a recorded zero; NOT_AVAILABLE means the data was not captured for the period.");
        if ("RECONSTRUCTED_SALES_ONLY".equals(report.reportType())) {
            row(csv, "stock", "availability", "", "", "", "", "", "", "", NOT_AVAILABLE, HISTORICAL_STOCK_NOTE);
        }
        boolean stockAvailable = !"RECONSTRUCTED_SALES_ONLY".equals(report.reportType());
        String stockStatus = stockAvailable ? AVAILABLE : NOT_AVAILABLE;
        String stockNote = stockAvailable ? "" : HISTORICAL_STOCK_NOTE;
        row(csv, "stock", "added", "", "", "", "", stockAvailable ? report.stockAdded() : NOT_AVAILABLE, "", "", stockStatus, stockNote);
        row(csv, "stock", "removed_including_adjustments", "", "", "", "", stockAvailable ? report.stockRemoved() : NOT_AVAILABLE, "", "", stockStatus, stockNote);
        row(csv, "sales", "fulfilled_units", "", "", "", "", report.unitsSold(), "", "", AVAILABLE, "Counts fulfilled orders in this period; zero means no fulfilled units were recorded.");
        row(csv, "stock", "closing_retail_value", "", "", "", "", stockAvailable ? report.inventoryUnits() : NOT_AVAILABLE,
            stockAvailable ? report.totalInventoryValue() : NOT_AVAILABLE, "", stockStatus, stockNote);
        row(csv, "stock", "closing_cost_value", "", "", "", "", "", stockAvailable ? report.inventoryCost() : NOT_AVAILABLE, "", stockStatus, stockNote);
        row(csv, "finance", "fulfilled_revenue_and_gross_profit_before_fees", "", "", "", "", "", report.revenue(), report.grossProfit(), AVAILABLE, "Sales use fulfilled-order purchase snapshots; gross profit is before platform fees.");
        row(csv, "finance", "recorded_platform_fees_on_fulfilled_orders", "", "", "", "", "", report.totalFeesCollected(), "", AVAILABLE, "Recorded fees only; this is not payment-provider settlement confirmation.");
        row(csv, "stock", "stagnant_listings", "", "", "", "", stockAvailable ? report.stagnantListings() : NOT_AVAILABLE, "", "", stockStatus, stockNote);
        row(csv, "stock", "stagnant_units_and_retail_value", "", "", "", "", stockAvailable ? report.stagnantUnits() : NOT_AVAILABLE,
            stockAvailable ? report.stagnantValue() : NOT_AVAILABLE, "", stockStatus, stockNote);
        for (var card : report.topByRevenue()) row(csv, "top_revenue", "", card.cardId(), card.name(), "", "", card.unitsSold(), card.revenue(), card.grossProfit(), AVAILABLE, "");
        for (var card : report.topByProfit()) row(csv, "top_profit", "", card.cardId(), card.name(), "", "", card.unitsSold(), card.revenue(), card.grossProfit(), AVAILABLE, "");
        for (var item : report.stagnantInventory()) row(csv, "stagnant", "retail_value", item.cardId(), item.name(), item.productId(), item.sellerId(), item.onHand(), item.inventoryValue(), "", AVAILABLE, "");
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

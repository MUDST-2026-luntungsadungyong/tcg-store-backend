package com.mythix.tcg.admin.application;

import com.mythix.tcg.admin.api.WeeklyReportDto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WeeklyReportService {
    private static final String TRACKED = "TRACKED";
    private static final String RECONSTRUCTED_SALES_ONLY = "RECONSTRUCTED_SALES_ONLY";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record SaleItem(long productId, String cardId, String name, int quantity,
                           BigDecimal unitPrice, BigDecimal unitCost) {}
    private record Stock(long productId, long sellerId, String cardId, String name, long units,
                         BigDecimal price, BigDecimal cost, LocalDateTime listedAt) {}

    public static LocalDateTime latestCutoff(LocalDateTime utcNow) {
        LocalDateTime cutoff = utcNow.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .atTime(23, 59);
        return cutoff.isAfter(utcNow) ? cutoff.minusWeeks(1) : cutoff;
    }

    @Transactional(readOnly = true)
    public History history(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_weekly_reports", Long.class);
        var items = jdbc.query("""
            SELECT week_end_date, generated_at, total_inventory_value, total_fees_collected, report_type
            FROM admin_weekly_reports ORDER BY week_end_date DESC LIMIT ? OFFSET ?
            """, (r, n) -> new HistoryItem(r.getDate(1).toLocalDate(), r.getTimestamp(2).toLocalDateTime(),
                r.getBigDecimal(3), r.getBigDecimal(4), r.getString(5)), size, page * size);
        return new History(items, page, size, count, (int) ((count + size - 1) / size), trackingSince());
    }

    @Transactional(readOnly = true)
    public Report get(LocalDate weekEnd) {
        if (weekEnd != null && weekEnd.getDayOfWeek() != DayOfWeek.SUNDAY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weekEnd must be a Sunday (UTC)");
        }
        List<String> rows = weekEnd == null
            ? jdbc.queryForList("SELECT payload FROM admin_weekly_reports ORDER BY week_end_date DESC LIMIT 1", String.class)
            : jdbc.queryForList("SELECT payload FROM admin_weekly_reports WHERE week_end_date = ?", String.class, weekEnd);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved weekly report for this period");
        return json.readValue(rows.getFirst(), Report.class);
    }

    private LocalDateTime trackingSince() {
        return jdbc.queryForObject("SELECT tracking_since FROM admin_report_state WHERE id = 1",
            (r, n) -> r.getTimestamp(1).toLocalDateTime());
    }

    /** Serialized across application replicas. Only full, tracked periods may be generated. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int generateDue(LocalDateTime utcNow) {
        jdbc.queryForObject("SELECT id FROM admin_report_state WHERE id = 1 FOR UPDATE", Integer.class);
        LocalDateTime since = trackingSince();
        int reconstructed = generateReconstructedSalesReports(since, utcNow);
        LocalDateTime end = latestCutoff(since).plusWeeks(1);
        if (end.minusWeeks(1).isBefore(since)) end = end.plusWeeks(1);
        LocalDateTime latest = latestCutoff(utcNow.minusMinutes(1)); // allow boundary transactions to finish
        int generated = 0;
        for (; !end.isAfter(latest) && generated < 52; end = end.plusWeeks(1)) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM admin_weekly_reports WHERE week_end_date = ?",
                Long.class, end.toLocalDate()) > 0) continue;
            // Wait for in-flight fact writers (including transactions longer than the grace
            // minute). Keep the cut-off immutable while reading all report dimensions.
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                    try (var statement = connection.createStatement()) {
                        statement.execute("LOCK TABLE admin_inventory_events, admin_order_events IN SHARE MODE");
                    }
                }
                return null;
            });
            Report report = calculate(end.minusWeeks(1), end, utcNow);
            jdbc.update("""
                INSERT INTO admin_weekly_reports(week_start_date, week_end_date, period_start, period_end,
                    total_inventory_value, total_fees_collected, top_performing_cards,
                    stagnant_inventory_summary, payload, generated_at, report_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, report.periodStart().toLocalDate(), report.weekEndDate(), report.periodStart(), end,
                report.totalInventoryValue(), report.totalFeesCollected(), json.writeValueAsString(report.topByRevenue()),
                json.writeValueAsString(report.stagnantInventory()), json.writeValueAsString(report), utcNow, TRACKED);
            generated++;
        }
        return reconstructed + generated;
    }

    /** Rebuilds only historical sales facts. Old inventory snapshots were never recorded. */
    private int generateReconstructedSalesReports(LocalDateTime since, LocalDateTime generatedAt) {
        LocalDateTime firstFulfilled = jdbc.queryForObject(
            "SELECT MIN(occurred_at) FROM admin_order_events WHERE phase = 'FULFILLED'",
            (r, n) -> r.getTimestamp(1) == null ? null : r.getTimestamp(1).toLocalDateTime());
        if (firstFulfilled == null) return 0;

        LocalDateTime end = latestCutoff(firstFulfilled).plusWeeks(1);
        LocalDateTime lastHistoricalEnd = latestCutoff(since);
        int generated = 0;
        for (; !end.isAfter(lastHistoricalEnd) && generated < 52; end = end.plusWeeks(1)) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM admin_weekly_reports WHERE week_end_date = ?",
                Long.class, end.toLocalDate()) > 0) continue;
            Report report = calculateHistoricalSales(end.minusWeeks(1), end, generatedAt);
            jdbc.update("""
                INSERT INTO admin_weekly_reports(week_start_date, week_end_date, period_start, period_end,
                    total_inventory_value, total_fees_collected, top_performing_cards,
                    stagnant_inventory_summary, payload, generated_at, report_type)
                VALUES (?, ?, ?, ?, NULL, ?, ?, '[]', ?, ?, ?)
                """, report.periodStart().toLocalDate(), report.weekEndDate(), report.periodStart(), end,
                report.totalFeesCollected(), json.writeValueAsString(report.topByRevenue()),
                json.writeValueAsString(report), generatedAt, RECONSTRUCTED_SALES_ONLY);
            generated++;
        }
        return generated;
    }

    private Report calculateHistoricalSales(LocalDateTime start, LocalDateTime end, LocalDateTime generatedAt) {
        Map<String, CardPerformance> cards = new HashMap<>();
        BigDecimal fees = BigDecimal.ZERO;
        var events = jdbc.queryForList("""
            SELECT platform_fee, items_json FROM admin_order_events
            WHERE phase = 'FULFILLED' AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at, id
            """, start, end);
        for (var event : events) {
            fees = fees.add((BigDecimal) event.get("platform_fee"));
            List<SaleItem> items = json.readValue((String) event.get("items_json"), new TypeReference<List<SaleItem>>() {});
            for (SaleItem item : items) {
                BigDecimal quantity = BigDecimal.valueOf(item.quantity());
                BigDecimal revenue = item.unitPrice().multiply(quantity);
                BigDecimal profit = item.unitPrice().subtract(item.unitCost()).multiply(quantity);
                CardPerformance previous = cards.getOrDefault(item.cardId(),
                    new CardPerformance(item.cardId(), item.name(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
                cards.put(item.cardId(), new CardPerformance(item.cardId(), item.name(),
                    previous.unitsSold() + item.quantity(), previous.revenue().add(revenue),
                    previous.grossProfit().add(profit)));
            }
        }
        var byRevenue = cards.values().stream().sorted(Comparator.comparing(CardPerformance::revenue).reversed()
            .thenComparing(CardPerformance::cardId)).limit(10).toList();
        var byProfit = cards.values().stream().sorted(Comparator.comparing(CardPerformance::grossProfit).reversed()
            .thenComparing(CardPerformance::cardId)).limit(10).toList();
        return new Report(end.toLocalDate(), start, end, generatedAt, null, null,
            cards.values().stream().mapToLong(CardPerformance::unitsSold).sum(), null, null, null,
            cards.values().stream().map(CardPerformance::revenue).reduce(BigDecimal.ZERO, BigDecimal::add),
            cards.values().stream().map(CardPerformance::grossProfit).reduce(BigDecimal.ZERO, BigDecimal::add),
            fees, byRevenue, byProfit, null, null, null, List.of(), RECONSTRUCTED_SALES_ONLY);
    }

    private Report calculate(LocalDateTime start, LocalDateTime end, LocalDateTime generatedAt) {
        List<Stock> stocks = jdbc.query("""
            SELECT * FROM (
                SELECT e.*, ROW_NUMBER() OVER (PARTITION BY local_card_id ORDER BY occurred_at DESC, id DESC) AS rn
                FROM admin_inventory_events e WHERE occurred_at < ?
            ) s WHERE rn = 1 AND on_hand > 0 ORDER BY local_card_id
            """, (r, n) -> new Stock(r.getLong("local_card_id"), r.getLong("seller_id"),
                r.getString("card_id"), r.getString("card_name"), r.getLong("on_hand"),
                r.getBigDecimal("price"), r.getBigDecimal("cost"), r.getTimestamp("listing_created_at").toLocalDateTime()), end);
        long added = jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity_delta),0) FROM admin_inventory_events
            WHERE occurred_at >= ? AND occurred_at < ? AND quantity_delta > 0
            """, Long.class, start, end);
        long removed = jdbc.queryForObject("""
            SELECT COALESCE(-SUM(quantity_delta),0) FROM admin_inventory_events
            WHERE occurred_at >= ? AND occurred_at < ? AND quantity_delta < 0
            """, Long.class, start, end);
        Map<String, CardPerformance> cards = new HashMap<>();
        Set<Long> recentlyTraded = new HashSet<>();
        BigDecimal fees = BigDecimal.ZERO;
        var events = jdbc.queryForList("""
            SELECT phase, platform_fee, items_json, occurred_at FROM admin_order_events
            WHERE occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at, id
            """, start.minusDays(60), end);
        for (var event : events) {
            LocalDateTime time = ((java.sql.Timestamp) event.get("occurred_at")).toLocalDateTime();
            List<SaleItem> items = json.readValue((String) event.get("items_json"), new TypeReference<List<SaleItem>>() {});
            if (!time.isBefore(end.minusDays(60))) items.forEach(item -> recentlyTraded.add(item.productId()));
            if (!"FULFILLED".equals(event.get("phase")) || time.isBefore(start)) continue;
            fees = fees.add((BigDecimal) event.get("platform_fee"));
            for (SaleItem item : items) {
                BigDecimal quantity = BigDecimal.valueOf(item.quantity());
                BigDecimal revenue = item.unitPrice().multiply(quantity);
                BigDecimal profit = item.unitPrice().subtract(item.unitCost()).multiply(quantity);
                CardPerformance previous = cards.getOrDefault(item.cardId(),
                    new CardPerformance(item.cardId(), item.name(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
                cards.put(item.cardId(), new CardPerformance(item.cardId(), item.name(),
                    previous.unitsSold() + item.quantity(), previous.revenue().add(revenue), previous.grossProfit().add(profit)));
            }
        }
        List<StagnantListing> stagnant = stocks.stream()
            .filter(s -> s.listedAt().isBefore(end.minusDays(60)) && !recentlyTraded.contains(s.productId()))
            .map(s -> new StagnantListing(s.productId(), s.sellerId(), s.cardId(), s.name(), s.units(),
                s.price().multiply(BigDecimal.valueOf(s.units())), s.listedAt())).toList();
        var byRevenue = cards.values().stream().sorted(Comparator.comparing(CardPerformance::revenue).reversed()
            .thenComparing(CardPerformance::cardId)).limit(10).toList();
        var byProfit = cards.values().stream().sorted(Comparator.comparing(CardPerformance::grossProfit).reversed()
            .thenComparing(CardPerformance::cardId)).limit(10).toList();
        return new Report(end.toLocalDate(), start, end, generatedAt, added, removed,
            cards.values().stream().mapToLong(CardPerformance::unitsSold).sum(),
            stocks.stream().mapToLong(Stock::units).sum(),
            stocks.stream().map(s -> s.price().multiply(BigDecimal.valueOf(s.units()))).reduce(BigDecimal.ZERO, BigDecimal::add),
            stocks.stream().map(s -> s.cost().multiply(BigDecimal.valueOf(s.units()))).reduce(BigDecimal.ZERO, BigDecimal::add),
            cards.values().stream().map(CardPerformance::revenue).reduce(BigDecimal.ZERO, BigDecimal::add),
            cards.values().stream().map(CardPerformance::grossProfit).reduce(BigDecimal.ZERO, BigDecimal::add), fees,
            byRevenue, byProfit, (long) stagnant.size(), stagnant.stream().mapToLong(StagnantListing::onHand).sum(),
            stagnant.stream().map(StagnantListing::inventoryValue).reduce(BigDecimal.ZERO, BigDecimal::add), stagnant, TRACKED);
    }
}

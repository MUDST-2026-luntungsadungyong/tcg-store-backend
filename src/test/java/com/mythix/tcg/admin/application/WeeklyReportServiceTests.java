package com.mythix.tcg.admin.application;

import com.mythix.tcg.admin.api.WeeklyReportDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

class WeeklyReportServiceTests {
    private JdbcTemplate jdbc;
    private WeeklyReportService service;
    private final LocalDateTime start = LocalDateTime.parse("2026-11-01T23:59:00");
    private final LocalDateTime end = start.plusWeeks(1);

    @BeforeEach
    void setup() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        String ddl = new ClassPathResource("db/migration/V31__admin_weekly_reports.sql").getContentAsString(StandardCharsets.UTF_8);
        var tables = Pattern.compile("(?s)CREATE TABLE \\w+.*?;").matcher(ddl);
        while (tables.find()) jdbc.execute(tables.group());
        jdbc.update("INSERT INTO admin_report_state VALUES (1, ?)", start);
        service = new WeeklyReportService(jdbc, JsonMapper.builder().build());
    }

    @Test
    void aggregatesSnapshotsUsingHalfOpenUtcPeriodsAndOnlyFulfilledSales() {
        stock(1, 0, 20, 10, 4, start.minusDays(1), end.minusDays(100));
        stock(1, 5, 25, 10, 4, start, end.minusDays(100));
        stock(1, -3, 22, 10, 4, start.plusDays(2), end.minusDays(100));
        stock(1, 78, 100, 999, 5, end, end.minusDays(100)); // next week, excluded
        stock(2, 0, 10, 20, 5, start.minusDays(1), end.minusDays(61));
        stock(3, 0, 1, 5, 1, start.minusDays(1), end.minusDays(60)); // exactly 60, excluded
        sale(1, "FULFILLED", 3, 10, 4, "3.00", start);
        sale(2, "SENT", 8, 900, 1, "99", start.plusDays(1));
        sale(3, "FULFILLED", 10, 100, 0, "100", end); // next week
        assertThat(service.generateDue(end.plusMinutes(1))).isEqualTo(1);
        var report = service.get(end.toLocalDate());
        assertThat(report.stockAdded()).isEqualTo(5);
        assertThat(report.stockRemoved()).isEqualTo(3);
        assertThat(report.unitsSold()).isEqualTo(3);
        assertThat(report.inventoryUnits()).isEqualTo(33);
        assertThat(report.totalInventoryValue()).isEqualByComparingTo("425");
        assertThat(report.inventoryCost()).isEqualByComparingTo("139");
        assertThat(report.revenue()).isEqualByComparingTo("30");
        assertThat(report.grossProfit()).isEqualByComparingTo("18");
        assertThat(report.totalFeesCollected()).isEqualByComparingTo("3");
        assertThat(report.stagnantListings()).isEqualTo(1);
        assertThat(report.stagnantUnits()).isEqualTo(10);
        assertThat(report.stagnantValue()).isEqualByComparingTo("200");
        assertThat(report.topByRevenue()).hasSize(1);
    }

    @Test
    void savedHistoryIsImmutableAndRerunsAreIdempotent() {
        stock(1, 2, 2, 10, 3, start.plusDays(1), start);
        service.generateDue(end.plusMinutes(1));
        var original = service.get(null);
        jdbc.update("UPDATE admin_inventory_events SET price = 999");
        assertThat(service.generateDue(end.plusDays(1))).isZero();
        assertThat(service.get(end.toLocalDate())).isEqualTo(original);
        var history = service.history(0, 20);
        assertThat(history.totalElements()).isEqualTo(1);
        assertThat(history.content().getFirst().weekEndDate()).isEqualTo(end.toLocalDate());
        assertThat(service.history(1, 20).content()).isEmpty();
    }

    @Test
    void partialTrackingWeekAndGraceMinuteAreNotReported() {
        jdbc.update("UPDATE admin_report_state SET tracking_since = ?", start.plusSeconds(1));
        assertThat(service.generateDue(end.plusMinutes(1))).isZero();
        jdbc.update("UPDATE admin_report_state SET tracking_since = ?", start);
        assertThat(service.generateDue(end)).isZero();
        assertThat(service.generateDue(end.plusSeconds(59))).isZero();
        assertThat(service.generateDue(end.plusMinutes(1))).isEqualTo(1);
    }

    @Test
    void emptyWeekIsARealZeroReportAndMissedWeeksCatchUp() {
        assertThat(service.history(0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.get(null)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.generateDue(end.plusWeeks(1).plusMinutes(1))).isEqualTo(2);
        assertThat(service.get(null).revenue()).isZero();
        assertThat(service.get(null).topByRevenue()).isEmpty();
        assertThat(service.history(0, 1).totalPages()).isEqualTo(2);
    }

    @Test
    void rankingsAreIndependentlyLimitedToTenAndNegativeProfitIsPreserved() {
        for (int i = 1; i <= 12; i++) sale(i, "FULFILLED", 1, 100 + i, i * 20, "0", start.plusDays(1));
        service.generateDue(end.plusMinutes(1));
        var report = service.get(null);
        assertThat(report.topByRevenue()).hasSize(10);
        assertThat(report.topByProfit()).hasSize(10);
        assertThat(report.topByRevenue().getFirst().cardId()).isEqualTo("card-12");
        assertThat(report.topByProfit().getFirst().cardId()).isEqualTo("card-1");
        assertThat(report.topByRevenue().getFirst().grossProfit()).isNegative();
        assertThat(report.unitsSold()).isEqualTo(12);
    }

    @Test
    void validatesDatesAndPagination() {
        assertThatThrownBy(() -> service.get(LocalDate.parse("2026-11-09"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.history(-1, 20)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.history(0, 101)).isInstanceOf(ResponseStatusException.class);
        assertThat(WeeklyReportService.latestCutoff(end.minusSeconds(1))).isEqualTo(start);
        assertThat(WeeklyReportService.latestCutoff(end)).isEqualTo(end);
    }

    @Test
    void csvContainsAllDimensionsAndEscapesSpreadsheetFormulasAndQuotes() {
        assertThat(WeeklyReportCsv.cell("  =HYPERLINK(\"evil\")")).startsWith("\"'  =").contains("\"\"evil\"\"");
        assertThat(WeeklyReportCsv.cell("\t@SUM(1)")).startsWith("\"'");
        assertThat(WeeklyReportCsv.cell(new BigDecimal("-20"))).isEqualTo("\"-20\"");
        service.generateDue(end.plusMinutes(1));
        String csv = new String(WeeklyReportCsv.export(service.get(null)), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF").contains("closing_retail_value", "recorded_platform_fees", "end_utc_exclusive");
        assertThat(csv).contains("\r\n");
    }

    private void stock(long id, int delta, int units, int price, int cost, LocalDateTime time, LocalDateTime listed) {
        jdbc.update("""
            INSERT INTO admin_inventory_events(local_card_id, seller_id, card_id, card_name,
                quantity_delta, on_hand, price, cost, listing_created_at, occurred_at)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, "card-" + id, "Card " + id, delta, units, price, cost, listed, time);
    }

    private void sale(long id, String phase, int quantity, int price, int cost, String fee, LocalDateTime time) {
        String payload = JsonMapper.builder().build().writeValueAsString(List.of(new WeeklyReportService.SaleItem(
            1, "card-" + id, "Card " + id, quantity, BigDecimal.valueOf(price), BigDecimal.valueOf(cost))));
        jdbc.update("INSERT INTO admin_order_events(order_id, phase, platform_fee, items_json, occurred_at) VALUES (?, ?, ?, ?, ?)",
            id, phase, new BigDecimal(fee), payload, time);
    }
}

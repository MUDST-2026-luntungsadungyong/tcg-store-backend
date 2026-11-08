package com.mythix.tcg.admin.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;

/** Run only against a disposable DB, e.g. -Dweekly.postgres.url=jdbc:postgresql://localhost:35437/weekly_report_test */
@EnabledIfSystemProperty(named = "weekly.postgres.url", matches = ".+")
class WeeklyReportPostgresTests {
    private JdbcTemplate jdbc;
    private WeeklyReportService service;
    private TransactionTemplate tx;
    private LocalDateTime end;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(System.getProperty("weekly.postgres.url"), "postgres",
            System.getProperty("weekly.postgres.password", "weekly-test-only"));
        // A unique schema protects other test runs and the URL's default schema.
        String schema = "weekly_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
        ds.setUrl(System.getProperty("weekly.postgres.url") + "?currentSchema=" + schema);
        jdbc = new JdbcTemplate(ds);
        service = new WeeklyReportService(jdbc, JsonMapper.builder().build());
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        end = WeeklyReportService.latestCutoff(LocalDateTime.now(ZoneOffset.UTC)).plusWeeks(1);
        jdbc.update("UPDATE admin_report_state SET tracking_since = ?", end.minusWeeks(1));
        jdbc.update("INSERT INTO users(id,email,password,full_name) VALUES (1,'weekly@example.test','hash','Report seller')");
        jdbc.update("""
            INSERT INTO global_cards(id,name,supertype,set_name,card_number,image_small,image_large)
            VALUES ('test-card','=DANGEROUS, ""Card""','Pokemon','Test','1','','')
            """);
    }

    @AfterEach
    void cleanup() {
        if (jdbc == null) return;
        String schema = jdbc.queryForObject("SELECT current_schema()", String.class);
        if (schema != null && schema.matches("weekly_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void migrationsAndTriggersTrackRestocksShipmentsFeesAndRollbackAtomically() {
        jdbc.update("""
            INSERT INTO local_cards(id,seller_id,global_card_id,custom_image_url,condition,language,price,cost,on_hand)
            VALUES (1,1,'test-card','','NM','EN',100,60,10)
            """);
        jdbc.update("UPDATE local_cards SET on_hand = 15 WHERE id = 1");
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE local_cards SET on_hand = 100 WHERE id = 1");
            status.setRollbackOnly();
        });
        jdbc.update("""
            INSERT INTO orders(id,buyer_id,order_number,idempotency_key,status,total_amount,platform_fee)
            VALUES (1,1,'weekly-1','weekly-1','SENT',200,10)
            """);
        jdbc.update("""
            INSERT INTO order_items(order_id,local_card_id,seller_id,global_card_id,card_name_snapshot,
                shop_name_snapshot,price_at_purchase,cost_at_purchase,quantity)
            VALUES (1,1,1,'test-card','Card snapshot','Shop',100,60,2)
            """);
        jdbc.update("UPDATE local_cards SET on_hand = 13 WHERE id = 1");
        jdbc.update("INSERT INTO order_status_logs(order_id,to_status,changed_by_role) VALUES (1,'SENT','SELLER')");
        jdbc.update("INSERT INTO order_status_logs(order_id,to_status,changed_by_role) VALUES (1,'FULFILLED','BUYER')");
        jdbc.update("INSERT INTO order_status_logs(order_id,to_status,changed_by_role) VALUES (1,'FULFILLED','BUYER')");
        // Later edits must not change purchase snapshots used for revenue and profit.
        jdbc.update("UPDATE order_items SET price_at_purchase = 999 WHERE order_id = 1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_inventory_events", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_order_events", Long.class)).isEqualTo(2);
        Integer generated = tx.execute(status -> service.generateDue(end.plusMinutes(1)));
        assertThat(generated).isEqualTo(1);
        var report = service.get(null);
        assertThat(report.stockAdded()).isEqualTo(15);
        assertThat(report.stockRemoved()).isEqualTo(2);
        assertThat(report.totalInventoryValue()).isEqualByComparingTo("1300");
        assertThat(report.unitsSold()).isEqualTo(2);
        assertThat(report.revenue()).isEqualByComparingTo("200");
        assertThat(report.grossProfit()).isEqualByComparingTo("80");
        assertThat(report.totalFeesCollected()).isEqualByComparingTo("10");
    }

    @Test
    void concurrentSchedulersProduceExactlyOneSavedReport() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            java.util.concurrent.Callable<Integer> run = () -> {
                ready.countDown(); go.await();
                return tx.execute(status -> service.generateDue(end.plusMinutes(1)));
            };
            var first = pool.submit(run); var second = pool.submit(run);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS) + second.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(service.history(0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void deletedListingsRemainInHistoryButNotClosingStock() {
        jdbc.update("""
            INSERT INTO local_cards(id,seller_id,global_card_id,custom_image_url,condition,language,price,cost,on_hand)
            VALUES (1,1,'test-card','','NM','EN',100,60,10)
            """);
        jdbc.update("DELETE FROM local_cards WHERE id = 1");
        tx.executeWithoutResult(status -> service.generateDue(end.plusMinutes(1)));
        assertThat(service.get(null).inventoryUnits()).isZero();
        assertThat(service.get(null).stockAdded()).isEqualTo(10);
        assertThat(service.get(null).stockRemoved()).isEqualTo(10);
    }
}

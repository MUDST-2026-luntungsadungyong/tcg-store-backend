package com.mythix.tcg.tidb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional live validation for a provisioned TiDB Revision 5 database.
 *
 * <p>The test is skipped by default. CI enables it only in the manual TiDB
 * workflow and supplies the connection values through encrypted secrets.</p>
 */
@EnabledIfEnvironmentVariable(named = "TIDB_REVISION5_VALIDATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("tidb")
@TestPropertySource(properties = {
    "app.jwt.secret=revision5-live-validation-test-secret-32-bytes-minimum",
    "app.jwt.expiration-minutes=60"
})
class TidbRevision5LiveValidationTests {

    private static final Set<String> REQUIRED_TABLES = Set.of(
        "users", "seller_profiles", "audit_logs", "global_cards", "local_cards",
        "cart_items", "orders", "order_items", "order_status_logs", "collection_items"
    );

    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
        "global_cards", Set.of("id", "name", "is_active", "created_at", "updated_at"),
        "local_cards", Set.of("id", "seller_id", "global_card_id", "condition", "language", "status"),
        "cart_items", Set.of("user_id", "local_card_id", "quantity"),
        "orders", Set.of("buyer_id", "idempotency_key", "platform_fee", "seller_net_amount"),
        "order_items", Set.of("order_id", "local_card_id", "global_card_id", "card_name_snapshot", "shop_name_snapshot"),
        "collection_items", Set.of("user_id", "global_card_id", "condition", "language", "quantity")
    );

    @Test
    void targetDatabaseHasRevision5TablesColumnsAndNoLegacyCatalogTables() throws SQLException {
        String url = requiredEnvironment("TIDB_JDBC_URL");
        String username = requiredEnvironment("TIDB_USER");
        String password = requiredEnvironment("TIDB_PASSWORD");

        assertThat(url).contains("/tcg_store_revision5");
        assertThat(url).doesNotContain("tcg_store_test");
        assertThat(url).doesNotContain("password=");

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertThat(queryString(connection, "SELECT DATABASE()")).isEqualTo("tcg_store_revision5");
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'"))
                .isEqualTo(REQUIRED_TABLES.size());

            for (String table : REQUIRED_TABLES) {
                assertThat(queryLong(connection,
                    "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?", table))
                    .as("Revision 5 table %s", table)
                    .isEqualTo(1);
            }

            for (Map.Entry<String, Set<String>> entry : REQUIRED_COLUMNS.entrySet()) {
                for (String column : entry.getValue()) {
                    assertThat(queryLong(connection,
                        "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                        entry.getKey(), column))
                        .as("Revision 5 column %s.%s", entry.getKey(), column)
                        .isEqualTo(1);
                }
            }

            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name IN ('cards', 'products')"))
                .isZero();

            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM local_cards lc LEFT JOIN users u ON u.id = lc.seller_id "
                    + "WHERE u.id IS NULL")).isZero();
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM local_cards lc LEFT JOIN global_cards gc ON gc.id = lc.global_card_id "
                    + "WHERE gc.id IS NULL")).isZero();
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM collection_items ci LEFT JOIN global_cards gc "
                    + "ON gc.id = ci.global_card_id WHERE gc.id IS NULL")).isZero();
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM order_items oi LEFT JOIN orders o ON o.id = oi.order_id "
                    + "WHERE o.id IS NULL")).isZero();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as("Environment variable %s", name).isNotBlank();
        return value;
    }

    private static long queryLong(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }

    private static void bind(PreparedStatement statement, String[] parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setString(index + 1, parameters[index]);
        }
    }
}

package com.mythix.tcg.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class OrderContactFlowMigrationTest {

    @Test
    void migratesLegacyAuctionOrdersAndRemovesSlipStorage() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:order_contact_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            createLegacySchema(connection);

            new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V17__order_contact_flow_remove_payment_proof.sql"
            )).populate(connection);

            assertThat(status(connection, "orders", 1)).isEqualTo("AWAITING_BUYER_CONTACT");
            assertThat(status(connection, "orders", 2)).isEqualTo("AWAITING_BUYER_CONTACT");
            assertThat(status(connection, "orders", 3)).isEqualTo("PENDING");

            assertThat(status(connection, "auctions", 11)).isEqualTo("AWAITING_BUYER_CONTACT");
            assertThat(status(connection, "auctions", 12)).isEqualTo("AWAITING_BUYER_CONTACT");
            assertThat(status(connection, "auctions", 13)).isEqualTo("ACTIVE");

            try (ResultSet tables = connection.getMetaData().getTables(null, null, "AUCTION_PAYMENT_PROOFS", null)) {
                assertThat(tables.next()).isFalse();
            }
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, "AUCTIONS", "PAYMENT_DEADLINE")) {
                assertThat(columns.next()).isFalse();
            }
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, "ORDERS", "STATUS")) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(32);
            }
        }
    }

    private void createLegacySchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
            statement.execute("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(20) NOT NULL,
                    CONSTRAINT orders_status_check CHECK (
                        status IN ('PENDING', 'CONFIRMED', 'SENT', 'FULFILLED', 'CANCELLED', 'DEFAULTED')
                    )
                )
                """);
            statement.execute("""
                CREATE TABLE auctions (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(24) NOT NULL,
                    order_id BIGINT,
                    payment_deadline TIMESTAMP WITH TIME ZONE,
                    CONSTRAINT auctions_status_check CHECK (
                        status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'ENDED', 'AWAITING_PAYMENT', 'COMPLETED',
                                   'CANCELLED', 'BUYOUT_COMPLETED', 'RESERVE_NOT_MET', 'UNSOLD_NO_BIDS', 'DEFAULTED')
                    )
                )
                """);
            statement.execute("CREATE TABLE auction_payment_proofs (order_id BIGINT PRIMARY KEY, content BINARY)");

            statement.execute("INSERT INTO orders (id, status) VALUES (1, 'PENDING'), (2, 'PENDING'), (3, 'PENDING')");
            statement.execute("""
                INSERT INTO auctions (id, status, order_id) VALUES
                    (11, 'AWAITING_PAYMENT', 1),
                    (12, 'BUYOUT_COMPLETED', 2),
                    (13, 'ACTIVE', 3)
                """);
            statement.execute("INSERT INTO auction_payment_proofs (order_id, content) VALUES (1, X'01')");
        }
    }

    private String status(Connection connection, String table, long id) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT status FROM " + table + " WHERE id = " + id)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}

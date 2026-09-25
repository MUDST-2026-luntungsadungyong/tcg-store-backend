package com.mythix.tcg.tidb;

import com.mythix.tcg.cart.domain.CartItem;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.product.domain.Product;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression contract for the application-side Revision 5 cutover.
 *
 * <p>H2 tests use {@code create-drop}, so they can hide a mismatch between
 * JPA annotations and the real TiDB schema. These checks intentionally read
 * the immutable TiDB migration and inspect the entity annotations directly.
 * They should fail until the Java persistence mappings are cut over.</p>
 */
class TidbRevision5SchemaContractTests {

    private static final Path REVISION5_DDL = Path.of("infra/tidb/migrations/001_revision5_schema.sql");
    private static final Path REVISION5_DATA = Path.of("infra/tidb/migrations/002_revision5_data.sql");
    private static final Path TIDB_ENV = Path.of(".env.tidb.example");

    @Test
    void revision5MigrationsAreImmutableAndTargetTheRevision5Database() throws IOException {
        String ddl = readRequiredFile(REVISION5_DDL).toLowerCase(Locale.ROOT);
        String data = readRequiredFile(REVISION5_DATA).toLowerCase(Locale.ROOT);

        assertThat(ddl)
            .contains("create database if not exists tcg_store_revision5")
            .contains("use tcg_store_revision5")
            .contains("create table if not exists global_cards")
            .contains("create table if not exists local_cards")
            .contains("create table if not exists audit_logs")
            .contains("create table if not exists order_status_logs")
            .doesNotContain("drop database")
            .doesNotContain("create table if not exists cards")
            .doesNotContain("create table if not exists products");

        assertThat(data)
            .contains("use tcg_store_revision5")
            .contains("from tcg_store_test.users")
            .contains("from tcg_store_test.cards")
            .contains("from tcg_store_test.products")
            .contains("on duplicate key update")
            .doesNotContain("drop database")
            .doesNotContain("drop table");
    }

    @Test
    void revision5DdlContainsTheColumnsThatPreventSilentLegacyFallback() throws IOException {
        String ddl = readRequiredFile(REVISION5_DDL).toLowerCase(Locale.ROOT);

        assertThat(ddl)
            .contains("global_card_id varchar(100) not null")
            .contains("local_card_id bigint not null")
            .contains("platform_fee decimal(12,2) not null")
            .contains("seller_net_amount decimal(12,2) not null")
            .contains("card_name_snapshot varchar(255) not null")
            .contains("shop_name_snapshot varchar(255) not null")
            .contains("unique key uq_cart_user_local_card")
            .contains("unique key uq_collection_user_card")
            .contains("check (language in ('en', 'jp', 'th'))")
            .contains("check (status in ('show', 'hide', 'out_of_stock'))");
    }

    @Test
    void tidbExampleSelectsRevision5WithoutCommittingCredentials() throws IOException {
        String env = readRequiredFile(TIDB_ENV);
        String jdbcUrl = valueFor(env, "TIDB_JDBC_URL");

        assertThat(valueFor(env, "SPRING_PROFILES_ACTIVE")).isEqualTo("tidb");
        assertThat(jdbcUrl).contains("/tcg_store_revision5?");
        assertThat(jdbcUrl).doesNotContain("tcg_store_test");
        assertThat(valueFor(env, "TIDB_USER")).isEqualTo("replace-with-tidb-user");
        assertThat(valueFor(env, "TIDB_PASSWORD")).isEqualTo("replace-with-generated-password");
        assertThat(valueFor(env, "APP_JWT_SECRET")).isEqualTo("replace-with-at-least-32-random-bytes");
        assertThat(env).doesNotContain("password=").doesNotContain("sslKey=");
    }

    @Test
    void criticalJpaEntitiesUseRevision5TableAndForeignKeyNames() throws ReflectiveOperationException {
        assertThat(tableName(Card.class)).isEqualTo("global_cards");
        assertThat(tableName(Product.class)).isEqualTo("local_cards");
        assertThat(tableName(CartItem.class)).isEqualTo("cart_items");
        assertThat(tableName(Order.class)).isEqualTo("orders");
        assertThat(tableName(OrderItem.class)).isEqualTo("order_items");
        assertThat(tableName(CollectionItem.class)).isEqualTo("collection_items");

        assertThat(joinColumn(Product.class, "seller")).isEqualTo("seller_id");
        assertThat(joinColumn(Product.class, "card")).isEqualTo("global_card_id");
        assertThat(joinColumn(CartItem.class, "product")).isEqualTo("local_card_id");
        assertThat(joinColumn(OrderItem.class, "product")).isEqualTo("local_card_id");
        assertThat(joinColumn(CollectionItem.class, "card")).isEqualTo("global_card_id");

        assertThat(columnName(Order.class, "idempotencyKey")).isEqualTo("idempotency_key");
        assertThat(columnName(Order.class, "platformFee")).isEqualTo("platform_fee");
        assertThat(columnName(Order.class, "sellerNetAmount")).isEqualTo("seller_net_amount");
        assertThat(columnName(OrderItem.class, "cardId")).isEqualTo("global_card_id");
        assertThat(columnName(OrderItem.class, "productNameSnapshot")).isEqualTo("card_name_snapshot");
        assertThat(columnName(OrderItem.class, "shopNameSnapshot")).isEqualTo("shop_name_snapshot");

        assertThat(uniqueConstraintColumns(CartItem.class))
            .contains(Set.of("user_id", "local_card_id"));
        assertThat(uniqueConstraintColumns(CollectionItem.class))
            .contains(Set.of("user_id", "global_card_id", "condition", "language"));
        assertThat(uniqueConstraintColumns(Order.class))
            .doesNotContain(Set.of("buyer_id", "idempotency_key"));
    }

    private static String readRequiredFile(Path path) throws IOException {
        assertThat(Files.exists(path)).as("Required contract file %s", path).isTrue();
        return Files.readString(path);
    }

    private static String valueFor(String env, String key) {
        return env.lines()
            .filter(line -> line.startsWith(key + "="))
            .map(line -> line.substring(key.length() + 1).trim())
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + key + " in .env.tidb.example"));
    }

    private static String tableName(Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);
        assertThat(table).as("@Table on %s", entityType.getName()).isNotNull();
        return table.name();
    }

    private static String joinColumn(Class<?> entityType, String fieldName) throws NoSuchFieldException {
        JoinColumn joinColumn = entityType.getDeclaredField(fieldName).getAnnotation(JoinColumn.class);
        assertThat(joinColumn).as("@JoinColumn on %s.%s", entityType.getSimpleName(), fieldName).isNotNull();
        return joinColumn.name();
    }

    private static String columnName(Class<?> entityType, String fieldName) throws NoSuchFieldException {
        Field field = entityType.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        assertThat(column).as("@Column on %s.%s", entityType.getSimpleName(), fieldName).isNotNull();
        return column.name();
    }

    private static Set<Set<String>> uniqueConstraintColumns(Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);
        assertThat(table).as("@Table on %s", entityType.getName()).isNotNull();
        return Arrays.stream(table.uniqueConstraints())
            .map(constraint -> Set.of(constraint.columnNames()))
            .collect(Collectors.toSet());
    }
}

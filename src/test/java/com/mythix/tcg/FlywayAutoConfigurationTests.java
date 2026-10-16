package com.mythix.tcg;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayAutoConfigurationTests {

    @Test
    void bootIntegrationRunsMigrationAndValidatesHistoryOnStartup() {
        contextRunner().withPropertyValues("spring.flyway.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Flyway.class);
            Flyway flyway = context.getBean(Flyway.class);
            assertThat(flyway.info().applied()).hasSize(1);
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_boot4_probe", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" AND \"type\" = 'SQL'", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void apiCanDisableFlywayWithoutInitializingMigrationTables() {
        contextRunner().withPropertyValues("spring.flyway.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(Flyway.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'FLYWAY_BOOT4_PROBE'", Integer.class)).isZero();
        });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:flyway_boot4_" + UUID.randomUUID(),
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.flyway.locations=classpath:db/flyway-acceptance"
            );
    }
}

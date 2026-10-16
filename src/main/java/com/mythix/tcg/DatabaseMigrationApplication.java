package com.mythix.tcg;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.flywaydb.core.Flyway;

/**
 * Runs Flyway and JPA schema validation with the same artifact used by the API,
 * then exits so Compose and Kubernetes can use it as a release gate.
 */
public final class DatabaseMigrationApplication {

    private DatabaseMigrationApplication() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TcgStoreBackendApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("migration")
            .run(args)) {
            // Fail closed even on an existing schema if migration auto-configuration
            // is accidentally removed or disabled in a future runtime artifact.
            context.getBean(Flyway.class).validate();
        }
    }
}

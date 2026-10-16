package com.mythix.tcg;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(HealthProbeIntegrationTests.TestHealthConfiguration.class)
class HealthProbeIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Autowired
    private AtomicReference<Health> databaseHealth;

    @Test
    void databaseOutageOnlyRemovesReadinessAndRecoversWithoutRestart() throws Exception {
        databaseHealth.set(Health.up().build());

        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));

        databaseHealth.set(Health.down().build());

        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"));
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));

        databaseHealth.set(Health.up().build());

        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void gracefulShutdownHasAnExplicitApplicationTimeout() {
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
            .isEqualTo("30s");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestHealthConfiguration {

        @Bean
        AtomicReference<Health> databaseHealth() {
            return new AtomicReference<>(Health.up().build());
        }

        @Bean
        HealthIndicator dbHealthIndicator(AtomicReference<Health> databaseHealth) {
            return databaseHealth::get;
        }
    }
}

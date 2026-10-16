package com.mythix.tcg.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetricsExposureIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void exposesPrometheusAndOnlyTheExplicitActuatorSurface() throws Exception {
        S3OperationObservation.record(meterRegistry, S3OperationObservation.Operation.UPLOAD, () -> { });
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/plain")))
            .andExpect(content().string(containsString("tcg_storage_operations_total")))
            .andExpect(content().string(containsString("tcg_storage_operation_duration_seconds_bucket")))
            .andExpect(content().string(containsString("operation=\"upload\"")))
            .andExpect(content().string(containsString("outcome=\"success\"")))
            .andExpect(header().exists("X-Request-ID"));

        mockMvc.perform(get("/actuator/beans"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk());
    }

    @Test
    void allowsScrapeWithoutJwtButKeepsOtherProtectedApiRoutesAuthenticated() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreservesExistingHeadersAndAllowsRequestIdCorrelation() throws Exception {
        mockMvc.perform(options("/api/products")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type,Accept,Idempotency-Key,X-Request-ID"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Request-ID")))
            .andExpect(header().string("Access-Control-Allow-Headers", containsString("Authorization")))
            .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Request-ID")));
    }
}

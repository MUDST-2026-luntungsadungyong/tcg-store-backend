package com.mythix.tcg.common.observability;

import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTests {

    private static final String REQUEST_ID = "safe-request-id-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void emitsParseableJsonAccessLogWithoutRequestCredentialsQueriesOrBody(CapturedOutput output) throws Exception {
        org.slf4j.Logger hikariPoolLog = LoggerFactory.getLogger("com.zaxxer.hikari.pool.HikariPool");
        org.slf4j.Logger hibernatePoolLog = LoggerFactory.getLogger("org.hibernate.orm.connections.pooling");
        org.slf4j.Logger generatedPasswordLog = LoggerFactory.getLogger(
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration");
        assertThat(hikariPoolLog.isInfoEnabled()).isFalse();
        assertThat(hibernatePoolLog.isInfoEnabled()).isFalse();
        assertThat(generatedPasswordLog.isWarnEnabled()).isFalse();
        hikariPoolLog.info("Added connection conn0: url=jdbc:h2:mem:jdbc-url-secret-sentinel");
        hibernatePoolLog.info("Database JDBC URL [jdbc:h2:mem:jdbc-url-secret-sentinel]");
        generatedPasswordLog.warn("Using generated security password: generated-password-secret-sentinel");

        mockMvc.perform(get("/api/health?search=query-secret-sentinel")
                .header("X-Request-ID", REQUEST_ID)
                .header("Authorization", "Bearer authorization-secret-sentinel")
                .header("Cookie", "session=cookie-secret-sentinel")
                .content("body-secret-sentinel"))
            .andExpect(status().isUnauthorized());

        String captured = output.getAll();
        String accessLogLine = captured.lines()
            .filter(line -> line.contains("http_access"))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("No structured http_access event was emitted"));
        JsonNode accessLog = objectMapper.readTree(accessLogLine);

        assertThat(accessLog.path("message").asText()).as("access event message").isEqualTo("http_access");
        assertThat(accessLog.path("level").asText()).isNotBlank();
        assertThat(accessLog.path("logger_name").asText()).contains("RequestCorrelationFilter");
        assertThat(accessLog.path("application").asText()).isEqualTo("tcg-store-backend");
        assertThat(accessLog.path("requestId").asText()).isEqualTo(REQUEST_ID);
        assertThat(accessLog.path("route").asText()).isEqualTo("UNKNOWN");
        assertThat(accessLog.path("method").asText()).isEqualTo("GET");
        assertThat(accessLog.path("status").asText()).isEqualTo("401");
        assertThat(accessLog.path("durationMs").asText()).matches("[0-9]+");
        assertThat(captured).doesNotContain(
            "query-secret-sentinel",
            "authorization-secret-sentinel",
            "cookie-secret-sentinel",
            "body-secret-sentinel",
            "jdbc-url-secret-sentinel",
            "jdbc:h2:mem:",
            "generated-password-secret-sentinel",
            "Using generated security password"
        );
    }
}

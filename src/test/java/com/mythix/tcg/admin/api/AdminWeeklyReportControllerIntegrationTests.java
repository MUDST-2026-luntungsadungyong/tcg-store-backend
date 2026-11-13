package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.application.WeeklyReportService;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminWeeklyReportControllerIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @MockitoBean WeeklyReportService reports;

    @Test
    void unauthenticatedCannotReadHistoryReportOrExport() throws Exception {
        for (String suffix : List.of("", "/history", "/export")) {
            mvc.perform(get("/api/admin/reports/weekly-summary" + suffix)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(reports);
    }

    @Test
    void buyerOrSellerCannotReadReports() throws Exception {
        for (String suffix : List.of("", "/history", "/export")) {
            mvc.perform(get("/api/admin/reports/weekly-summary" + suffix).header("Authorization", bearer("USER")))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(reports);
    }

    @Test
    void emptyHistoryIsExplicitAndResponsesAreNotCached() throws Exception {
        when(reports.history(0, 20)).thenReturn(new WeeklyReportDto.History(List.of(), 0, 20, 0, 0,
            LocalDateTime.parse("2026-11-01T23:59:00")));
        mvc.perform(get("/api/admin/reports/weekly-summary/history").header("Authorization", bearer("ADMIN")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void missingReportIs404AndInvalidDateIs400() throws Exception {
        when(reports.get(null)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved weekly report"));
        mvc.perform(get("/api/admin/reports/weekly-summary").header("Authorization", bearer("ADMIN"))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("WEEKLY_REPORT_NOT_FOUND"));
        mvc.perform(get("/api/admin/reports/weekly-summary/export").header("Authorization", bearer("ADMIN"))).andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("WEEKLY_REPORT_NOT_FOUND"));
        mvc.perform(get("/api/admin/reports/weekly-summary").header("Authorization", bearer("ADMIN"))
            .param("weekEnd", "not-a-date")).andExpect(status().isBadRequest());
    }

    @Test
    void reportAndAuthenticatedCsvUseTheSameSavedSnapshot() throws Exception {
        var date = LocalDate.parse("2026-11-08");
        var zero = java.math.BigDecimal.ZERO;
        var time = date.atTime(23, 59);
        when(reports.get(date)).thenReturn(new WeeklyReportDto.Report(date, time.minusWeeks(1), time, time.plusMinutes(1),
            0L, 0L, 0, 0L, zero, zero, zero, zero, zero, List.of(), List.of(), 0L, 0L, zero, List.of(), "TRACKED"));
        mvc.perform(get("/api/admin/reports/weekly-summary").header("Authorization", bearer("ADMIN")).param("weekEnd", date.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.weekEndDate").value(date.toString()));
        mvc.perform(get("/api/admin/reports/weekly-summary/export").header("Authorization", bearer("ADMIN")).param("weekEnd", date.toString()))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"weekly-report-2026-11-08.csv\""))
            .andExpect(header().string("Cache-Control", "no-store"));
        verify(reports, times(2)).get(date);
    }

    private String bearer(String role) {
        User user = users.save(User.builder()
            .email("weekly-report-" + UUID.randomUUID() + "@example.test")
            .password("test-hash")
            .fullName("Weekly report test user")
            .role(role)
            .build());
        return "Bearer " + jwt.generateToken(user);
    }
}

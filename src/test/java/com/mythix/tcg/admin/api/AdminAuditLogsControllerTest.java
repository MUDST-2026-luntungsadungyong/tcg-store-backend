package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogsControllerTest {

    @Mock
    private AuditLogRepository logs;

    @InjectMocks
    private AdminAuditLogsController controller;

    @Test
    void list_returnsAuditLogsWithActorDetailsWhenNoFilterProvided() {
        User actor = User.builder().id(1L).fullName("Platform Admin").email("admin@mythix.com").role("ADMIN").sellerStatus("NONE").build();
        AuditLog log = AuditLog.builder()
                .id(10L)
                .actor(actor)
                .action("APPROVE_SELLER")
                .targetType("SELLER_APPLICATION")
                .targetId("1")
                .details("Approved application")
                .createdAt(LocalDateTime.now())
                .build();

        when(logs.searchAuditLogs(eq(""), eq("ALL"), any(), eq("Y"), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(log)));

        Page<AuditLogResponse> result = controller.list(null, "ALL", null, null, null, null, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        AuditLogResponse response = result.getContent().get(0);
        assertThat(response.id()).isEqualTo("10");
        assertThat(response.action()).isEqualTo("APPROVE_SELLER");
        assertThat(response.actorId()).isEqualTo("1");
        assertThat(response.actorName()).isEqualTo("Platform Admin");
        assertThat(response.actorEmail()).isEqualTo("admin@mythix.com");
        assertThat(response.actorRole()).isEqualTo("ADMIN");
    }

    @Test
    void list_filtersByMultipleActionsAndDateRange() {
        AuditLog systemLog = AuditLog.builder()
                .id(11L)
                .actor(null)
                .action("UPDATE_STOCK")
                .targetType("INVENTORY")
                .targetId("5")
                .details("Updated stock count")
                .createdAt(LocalDateTime.now())
                .build();

        when(logs.searchAuditLogs(eq("stock"), eq("DETAILS"), eq(List.of("UPDATE_STOCK")), eq("N"), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(systemLog)));

        Page<AuditLogResponse> result = controller.list("stock", "DETAILS", List.of("UPDATE_STOCK"), null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 0, 20);

        assertThat(result.getContent()).hasSize(1);
        AuditLogResponse response = result.getContent().get(0);
        assertThat(response.actorName()).isEqualTo("System");
        assertThat(response.actorRole()).isEqualTo("SYSTEM");
    }
}

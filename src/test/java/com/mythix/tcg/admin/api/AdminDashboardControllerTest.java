package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.application.AdminSellerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin: AdminDashboardController Unit Tests")
class AdminDashboardControllerTest {

    @Mock
    private AdminSellerService adminSellerService;

    @InjectMocks
    private AdminDashboardController controller;

    @Test
    @DisplayName("dashboard() - returns aggregated platform metrics from AdminSellerService")
    void dashboard_returnsPlatformMetrics() {
        AdminDashboardResponse mockMetrics = new AdminDashboardResponse(
                100L, 2L, 80L, 5L, 15L, 2000L, 42L, 350L, new BigDecimal("125000.00")
        );

        when(adminSellerService.dashboard()).thenReturn(mockMetrics);

        AdminDashboardResponse response = controller.dashboard();

        assertThat(response).isNotNull();
        assertThat(response.totalUsers()).isEqualTo(100L);
        assertThat(response.totalAdmins()).isEqualTo(2L);
        assertThat(response.regularBuyers()).isEqualTo(80L);
        assertThat(response.pendingSellerApplications()).isEqualTo(5L);
        assertThat(response.approvedSellers()).isEqualTo(15L);
        assertThat(response.totalCards()).isEqualTo(2000L);
        assertThat(response.listedCards()).isEqualTo(42L);
        assertThat(response.totalOrders()).isEqualTo(350L);
        assertThat(response.totalOrderAmount()).isEqualByComparingTo("125000.00");

        verify(adminSellerService).dashboard();
    }
}

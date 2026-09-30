package com.mythix.tcg.admin.application;

import com.mythix.tcg.admin.api.AdminDashboardResponse;
import com.mythix.tcg.admin.api.AdminSellerApplicationResponse;
import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin Platform: AdminSellerService Unit Tests (SCRUM-48)")
class AdminSellerServiceUnitTest {

    @Mock
    private SellerProfileRepository sellerProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AdminSellerService adminSellerService;

    private User adminUser;
    private User applicantUser;
    private SellerProfile pendingProfile;

    @BeforeEach
    void setUp() {
        adminUser = User.builder()
            .id(8L)
            .email("admin@mythix.com")
            .fullName("Platform Administrator")
            .role("ADMIN")
            .sellerStatus("NONE")
            .build();

        applicantUser = User.builder()
            .id(9L)
            .email("misty@mythix.com")
            .fullName("Misty Cerulean")
            .phone("081-333-7777")
            .contactLine("misty_water")
            .contactFacebook("facebook.com/misty.water")
            .role("USER")
            .sellerStatus("PENDING")
            .build();

        pendingProfile = SellerProfile.builder()
            .id(2L)
            .user(applicantUser)
            .shopName("Cerulean Water Gym Cards")
            .shopDescription("Official Water Pokemon Cards")
            .idCardNumber("1100500987654")
            .bankName("SCB")
            .bankAccountNumber("987-6-54321-0")
            .bankAccountName("Misty Cerulean")
            .requestedAt(LocalDateTime.now().minusDays(1))
            .build();
    }

    @Nested
    @DisplayName("dashboard() - แอดมินดูสรุปข้อมูลสถิติภาพรวมระบบ")
    class DashboardTests {

        @Test
        @DisplayName("คำนวณและส่งคืนสถิติภาพรวม Dashboard ทั้งหมดถูกต้อง")
        void dashboard_returnsAggregatedMetrics() {
            when(userRepository.count()).thenReturn(100L);
            when(userRepository.countByRole("ADMIN")).thenReturn(2L);
            when(userRepository.countByRoleAndSellerStatus("USER", "NONE")).thenReturn(80L);
            when(userRepository.countBySellerStatus("PENDING")).thenReturn(5L);
            when(userRepository.countBySellerStatus("APPROVED")).thenReturn(15L);
            when(cardRepository.count()).thenReturn(2000L);
            when(productRepository.countByStatus("SHOW")).thenReturn(42L);
            when(orderRepository.count()).thenReturn(350L);
            when(orderRepository.paidOrFulfilledGmv()).thenReturn(new BigDecimal("125000.00"));
            when(orderRepository.findRecentOrders(any())).thenReturn(List.of());

            AdminDashboardResponse response = adminSellerService.dashboard();

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
            assertThat(response.trends()).hasSize(7);

            verify(userRepository).count();
            verify(cardRepository).count();
            verify(productRepository).countByStatus("SHOW");
            verify(orderRepository).count();
            verify(orderRepository).paidOrFulfilledGmv();
            verify(orderRepository).findRecentOrders(any());
        }
    }

    @Nested
    @DisplayName("pendingApplications() - แอดมินดูรายการคำขอเป็นผู้ขายที่รอการตรวจสอบ")
    class PendingApplicationsTests {

        @Test
        @DisplayName("ดึงรายการคำขอร้านค้าที่สถานะ PENDING และแปลงเป็น Response ครบถ้วน")
        void pendingApplications_returnsMappedResponses() {
            when(sellerProfileRepository.findAllByUserSellerStatusOrderByRequestedAtAsc("PENDING"))
                .thenReturn(List.of(pendingProfile));

            List<AdminSellerApplicationResponse> results = adminSellerService.pendingApplications();

            assertThat(results).hasSize(1);
            AdminSellerApplicationResponse item = results.get(0);
            assertThat(item.id()).isEqualTo("2");
            assertThat(item.userId()).isEqualTo("9");
            assertThat(item.applicantName()).isEqualTo("Misty Cerulean");
            assertThat(item.shopName()).isEqualTo("Cerulean Water Gym Cards");
            assertThat(item.sellerStatus()).isEqualTo("PENDING");

            verify(sellerProfileRepository).findAllByUserSellerStatusOrderByRequestedAtAsc("PENDING");
        }
    }

    @Nested
    @DisplayName("approve() - แอดมินอนุมัติคำขอเป็นผู้ขาย")
    class ApproveTests {

        @Test
        @DisplayName("อนุมัติสำเร็จ: อัปเดตสถานะเป็น APPROVED บันทึกผู้อนุมัติ วันที่อนุมัติ และบันทึก Audit Log")
        void approve_success() {
            when(sellerProfileRepository.findById(2L)).thenReturn(Optional.of(pendingProfile));
            when(userRepository.findById(8L)).thenReturn(Optional.of(adminUser));

            AdminSellerApplicationResponse response = adminSellerService.approve(2L, 8L);

            assertThat(response).isNotNull();
            assertThat(applicantUser.getSellerStatus()).isEqualTo("APPROVED");
            assertThat(pendingProfile.getApprovedBy()).isEqualTo(8L);
            assertThat(pendingProfile.getVerifiedAt()).isNotNull();

            ArgumentCaptor<AuditLog> logCaptor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(logCaptor.capture());
            AuditLog savedLog = logCaptor.getValue();
            assertThat(savedLog.getAction()).isEqualTo("APPROVE_SELLER");
            assertThat(savedLog.getTargetType()).isEqualTo("SELLER_PROFILE");
            assertThat(savedLog.getTargetId()).isEqualTo("2");
            assertThat(savedLog.getActor()).isEqualTo(adminUser);
        }

        @Test
        @DisplayName("เกิด SellerApplicationNotFoundException เมื่อไม่พบใบสมัคร ID ที่ระบุ")
        void approve_notFoundApplication_throwsException() {
            when(sellerProfileRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminSellerService.approve(999L, 8L))
                .isInstanceOf(SellerApplicationNotFoundException.class);

            verify(auditLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("เกิด SellerApplicationStateException เมื่อสถานะของผู้ยื่นคำขอไม่ได้เป็น PENDING")
        void approve_notPendingState_throwsException() {
            applicantUser.setSellerStatus("APPROVED");
            when(sellerProfileRepository.findById(2L)).thenReturn(Optional.of(pendingProfile));

            assertThatThrownBy(() -> adminSellerService.approve(2L, 8L))
                .isInstanceOf(SellerApplicationStateException.class);

            verify(auditLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("เกิด SellerApplicationNotFoundException เมื่อไม่พบบัญชีผู้ใช้ของ Admin ผู้อนุมัติ")
        void approve_adminNotFound_throwsException() {
            when(sellerProfileRepository.findById(2L)).thenReturn(Optional.of(pendingProfile));
            when(userRepository.findById(888L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminSellerService.approve(2L, 888L))
                .isInstanceOf(SellerApplicationNotFoundException.class);

            verify(auditLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("reject() - แอดมินปฏิเสธคำขอเป็นผู้ขาย")
    class RejectTests {

        @Test
        @DisplayName("ปฏิเสธสำเร็จ: อัปเดตสถานะเป็น REJECTED บันทึกผู้ปฏิเสธ วันที่ และเหตุผล พร้อมบันทึก Audit Log")
        void reject_success() {
            when(sellerProfileRepository.findById(2L)).thenReturn(Optional.of(pendingProfile));
            when(userRepository.findById(8L)).thenReturn(Optional.of(adminUser));

            String reason = "ภาพถ่ายบัตรประชาชนไม่ชัดเจน กรุณาถ่ายใหม่";
            AdminSellerApplicationResponse response = adminSellerService.reject(2L, 8L, reason);

            assertThat(response).isNotNull();
            assertThat(applicantUser.getSellerStatus()).isEqualTo("REJECTED");
            assertThat(pendingProfile.getRejectedBy()).isEqualTo(8L);
            assertThat(pendingProfile.getRejectedAt()).isNotNull();
            assertThat(pendingProfile.getRejectionReason()).isEqualTo(reason);

            ArgumentCaptor<AuditLog> logCaptor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(logCaptor.capture());
            AuditLog savedLog = logCaptor.getValue();
            assertThat(savedLog.getAction()).isEqualTo("REJECT_SELLER");
            assertThat(savedLog.getTargetType()).isEqualTo("SELLER_PROFILE");
            assertThat(savedLog.getTargetId()).isEqualTo("2");
            assertThat(savedLog.getDetails()).isEqualTo(reason);
        }

        @Test
        @DisplayName("เกิด SellerApplicationNotFoundException เมื่อไม่พบใบสมัคร ID ที่ต้องการปฏิเสธ")
        void reject_notFoundApplication_throwsException() {
            when(sellerProfileRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminSellerService.reject(999L, 8L, "Reason"))
                .isInstanceOf(SellerApplicationNotFoundException.class);

            verify(auditLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("เกิด SellerApplicationStateException เมื่อสถานะของผู้ยื่นไม่ได้เป็น PENDING ขณะปฏิเสธ")
        void reject_notPendingState_throwsException() {
            applicantUser.setSellerStatus("REJECTED");
            when(sellerProfileRepository.findById(2L)).thenReturn(Optional.of(pendingProfile));

            assertThatThrownBy(() -> adminSellerService.reject(2L, 8L, "Reason"))
                .isInstanceOf(SellerApplicationStateException.class);

            verify(auditLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("เกิด SellerApplicationNotFoundException เมื่อไม่พบบัญชีผู้ใช้ของ Admin ผู้ปฏิเสธ")
        void reject_adminNotFound_throwsException() {
            when(sellerProfileRepository.findById(2L)).thenReturn(Optional.of(pendingProfile));
            when(userRepository.findById(888L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminSellerService.reject(2L, 888L, "Reason"))
                .isInstanceOf(SellerApplicationNotFoundException.class);

            verify(auditLogRepository, never()).save(any());
        }
    }
}

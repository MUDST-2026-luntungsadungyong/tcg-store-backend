package com.mythix.tcg.admin.application;

import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.voucher.api.VoucherDto.AdminVoucherRequest;
import com.mythix.tcg.voucher.api.VoucherDto.AdminVoucherResponse;
import com.mythix.tcg.voucher.api.VoucherLiveUpdates;
import com.mythix.tcg.voucher.application.VoucherExceptions.VoucherCodeAlreadyExistsException;
import com.mythix.tcg.voucher.application.VoucherExceptions.VoucherNotFoundException;
import com.mythix.tcg.voucher.application.VoucherExceptions.VoucherValidationException;
import com.mythix.tcg.voucher.domain.Voucher;
import com.mythix.tcg.voucher.persistence.UserVoucherRepository;
import com.mythix.tcg.voucher.persistence.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin Vouchers: AdminVoucherService Unit Tests")
class AdminVoucherServiceUnitTest {

    @Mock
    private VoucherRepository voucherRepository;
    @Mock
    private UserVoucherRepository userVoucherRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AdminVoucherService adminVoucherService;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = User.builder().id(1L).email("admin@mythix.com").role("ADMIN").fullName("Admin").build();
    }

    @Test
    @DisplayName("Create: normalizes code to uppercase, clears maxDiscountAmount for FIXED_AMOUNT, logs audit & publishes live event")
    void shouldCreateNormalizedVoucher() {
        when(voucherRepository.existsByCodeIgnoreCase("SUMMER_50")).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(voucherRepository.saveAndFlush(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            v.setId(42L);
            v.setCreatedAt(LocalDateTime.now());
            v.setUpdatedAt(LocalDateTime.now());
            return v;
        });

        AdminVoucherRequest req = new AdminVoucherRequest(
            " summer_50 ",
            "Summer Deal",
            "Save 50 THB",
            "FIXED_AMOUNT",
            BigDecimal.valueOf(50),
            BigDecimal.valueOf(300),
            BigDecimal.valueOf(999), // Should be stripped for FIXED_AMOUNT
            100,
            "2026-11-10T00:00:00Z",
            "2026-12-10T23:59:59Z",
            true
        );

        AdminVoucherResponse created = adminVoucherService.createVoucher(1L, req);

        assertThat(created.id()).isEqualTo(42L);
        assertThat(created.code()).isEqualTo("SUMMER_50");
        assertThat(created.maxDiscountAmount()).isNull();
        assertThat(created.discountValue()).isEqualByComparingTo("50.00");

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("CREATE_VOUCHER");
        assertThat(auditCaptor.getValue().getTargetType()).isEqualTo("VOUCHER");
        assertThat(auditCaptor.getValue().getTargetId()).isEqualTo("42");

        verify(eventPublisher).publishEvent(new VoucherLiveUpdates.Changed("CREATED", 42L));
    }

    @Test
    @DisplayName("Create: throws VoucherCodeAlreadyExistsException when duplicate code exists")
    void shouldRejectDuplicateCode() {
        when(voucherRepository.existsByCodeIgnoreCase("WELCOME10")).thenReturn(true);

        AdminVoucherRequest req = new AdminVoucherRequest(
            "welcome10",
            "Welcome",
            "Desc",
            "PERCENTAGE",
            BigDecimal.TEN,
            BigDecimal.ZERO,
            BigDecimal.valueOf(100),
            50,
            "2026-11-10T00:00:00Z",
            "2026-12-10T00:00:00Z",
            true
        );

        assertThatThrownBy(() -> adminVoucherService.createVoucher(1L, req))
            .isInstanceOf(VoucherCodeAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Update: rejects lowering totalQuota below usedQuota")
    void shouldRejectTotalQuotaBelowUsedQuota() {
        Voucher existing = Voucher.builder()
            .id(5L)
            .code("MYTHIX50")
            .title("Old")
            .discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.valueOf(50))
            .minSpend(BigDecimal.valueOf(500))
            .totalQuota(50)
            .usedQuota(20)
            .startTime(LocalDateTime.now().minusDays(2))
            .endTime(LocalDateTime.now().plusDays(10))
            .isActive(true)
            .build();
        when(voucherRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(existing));

        AdminVoucherRequest req = new AdminVoucherRequest(
            "MYTHIX50",
            "Old",
            "Desc",
            "FIXED_AMOUNT",
            BigDecimal.valueOf(50),
            BigDecimal.valueOf(500),
            null,
            10, // lower than usedQuota 20
            "2026-11-10T00:00:00Z",
            "2026-12-10T00:00:00Z",
            true
        );

        assertThatThrownBy(() -> adminVoucherService.updateVoucher(1L, 5L, req))
            .isInstanceOf(VoucherValidationException.class)
            .hasMessageContaining("20");
    }

    @Test
    @DisplayName("Validation: rejects percentage over 100 and endTime <= startTime")
    void shouldRejectInvalidPercentageAndDates() {
        AdminVoucherRequest req = new AdminVoucherRequest(
            "BADCODE",
            "Bad Deal",
            "",
            "PERCENTAGE",
            BigDecimal.valueOf(120),
            BigDecimal.ZERO,
            null,
            10,
            "2026-12-10T00:00:00Z",
            "2026-11-10T00:00:00Z",
            true
        );

        assertThatThrownBy(() -> adminVoucherService.createVoucher(1L, req))
            .isInstanceOf(VoucherValidationException.class)
            .satisfies(ex -> {
                VoucherValidationException vex = (VoucherValidationException) ex;
                assertThat(vex.getFieldErrors()).containsKeys("discountValue", "endTime");
            });
    }

    @Test
    @DisplayName("Delete: detaches orders, deletes user vouchers, deletes voucher, logs audit & publishes live event")
    void shouldDeleteVoucherSafely() {
        Voucher existing = Voucher.builder()
            .id(9L)
            .code("FLASH100")
            .title("Flash")
            .discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.valueOf(100))
            .minSpend(BigDecimal.valueOf(1000))
            .totalQuota(100)
            .usedQuota(5)
            .startTime(LocalDateTime.now().minusDays(1))
            .endTime(LocalDateTime.now().plusDays(1))
            .isActive(true)
            .build();
        when(voucherRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(existing));
        when(orderRepository.detachVoucherByVoucherId(9L)).thenReturn(3);
        when(userVoucherRepository.deleteAllByVoucherId(9L)).thenReturn(7);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        adminVoucherService.deleteVoucher(1L, 9L);

        verify(orderRepository).detachVoucherByVoucherId(9L);
        verify(userVoucherRepository).deleteAllByVoucherId(9L);
        verify(voucherRepository).deleteById(9L);
        verify(auditLogRepository).save(argThat(log ->
            "DELETE_VOUCHER".equals(log.getAction()) && "VOUCHER".equals(log.getTargetType())
                && "9".equals(log.getTargetId()) && log.getDetails().contains("3 orders")
        ));
        verify(eventPublisher).publishEvent(new VoucherLiveUpdates.Changed("DELETED", 9L));
    }

    @Test
    @DisplayName("List & effectiveStatus: classifies ACTIVE, SCHEDULED, INACTIVE, EXPIRED, and EXHAUSTED")
    void shouldComputeEffectiveStatusesAndFilter() {
        LocalDateTime now = LocalDateTime.now();
        Voucher active = Voucher.builder().id(1L).code("A1").title("Active").discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.TEN).minSpend(BigDecimal.ZERO).totalQuota(10).usedQuota(2)
            .startTime(now.minusDays(1)).endTime(now.plusDays(1)).isActive(true).build();
        Voucher scheduled = Voucher.builder().id(2L).code("S1").title("Scheduled").discountType("PERCENTAGE")
            .discountValue(BigDecimal.TEN).minSpend(BigDecimal.ZERO).totalQuota(10).usedQuota(0)
            .startTime(now.plusDays(1)).endTime(now.plusDays(5)).isActive(true).build();
        Voucher inactive = Voucher.builder().id(3L).code("I1").title("Inactive").discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.TEN).minSpend(BigDecimal.ZERO).totalQuota(10).usedQuota(0)
            .startTime(now.minusDays(1)).endTime(now.plusDays(1)).isActive(false).build();
        Voucher expired = Voucher.builder().id(4L).code("E1").title("Expired").discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.TEN).minSpend(BigDecimal.ZERO).totalQuota(10).usedQuota(0)
            .startTime(now.minusDays(5)).endTime(now.minusDays(1)).isActive(true).build();
        Voucher exhausted = Voucher.builder().id(5L).code("X1").title("Exhausted").discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.TEN).minSpend(BigDecimal.ZERO).totalQuota(5).usedQuota(5)
            .startTime(now.minusDays(1)).endTime(now.plusDays(1)).isActive(true).build();

        when(voucherRepository.findAllByOrderByCreatedAtDescIdDesc())
            .thenReturn(List.of(active, scheduled, inactive, expired, exhausted));
        when(userVoucherRepository.countGroupedByVoucherId()).thenReturn(List.of());
        when(orderRepository.countOrdersGroupedByVoucherId()).thenReturn(List.of());

        List<AdminVoucherResponse> all = adminVoucherService.listVouchers(null, null, null);
        assertThat(all).extracting(AdminVoucherResponse::effectiveStatus)
            .containsExactly("ACTIVE", "SCHEDULED", "INACTIVE", "EXPIRED", "EXHAUSTED");

        List<AdminVoucherResponse> scheduledOnly = adminVoucherService.listVouchers(null, "SCHEDULED", null);
        assertThat(scheduledOnly).extracting(AdminVoucherResponse::code).containsExactly("S1");
    }

    @Test
    @DisplayName("Get missing voucher throws VoucherNotFoundException")
    void shouldThrowWhenVoucherMissing() {
        when(voucherRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adminVoucherService.getVoucher(999L))
            .isInstanceOf(VoucherNotFoundException.class);
    }
}

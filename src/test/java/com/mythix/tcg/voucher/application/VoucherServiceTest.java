package com.mythix.tcg.voucher.application;

import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.voucher.api.VoucherDto.VoucherValidateResponse;
import com.mythix.tcg.voucher.domain.UserVoucher;
import com.mythix.tcg.voucher.domain.Voucher;
import com.mythix.tcg.voucher.persistence.UserVoucherRepository;
import com.mythix.tcg.voucher.persistence.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Voucher System: VoucherService Unit Tests")
class VoucherServiceTest {

    @Mock
    private VoucherRepository voucherRepository;

    @Mock
    private UserVoucherRepository userVoucherRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private VoucherService voucherService;

    private Voucher fixedVoucher;
    private Voucher percentVoucher;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(1L).email("user@mythix.io").build();

        fixedVoucher = Voucher.builder()
            .id(1L)
            .code("MYTHIX50")
            .title("ส่วนลด ฿50")
            .discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.valueOf(50.00))
            .minSpend(BigDecimal.valueOf(300.00))
            .totalQuota(100)
            .usedQuota(5)
            .perUserLimit(1)
            .startTime(LocalDateTime.now().minusDays(1))
            .endTime(LocalDateTime.now().plusDays(10))
            .isActive(true)
            .build();

        percentVoucher = Voucher.builder()
            .id(2L)
            .code("WELCOME10")
            .title("ส่วนลด 10%")
            .discountType("PERCENTAGE")
            .discountValue(BigDecimal.valueOf(10.00))
            .minSpend(BigDecimal.valueOf(500.00))
            .maxDiscountAmount(BigDecimal.valueOf(100.00))
            .totalQuota(200)
            .usedQuota(0)
            .perUserLimit(1)
            .startTime(LocalDateTime.now().minusDays(1))
            .endTime(LocalDateTime.now().plusDays(30))
            .isActive(true)
            .build();
    }

    @Test
    @DisplayName("Fixed discount: applies flat deduction correctly")
    void shouldCalculateFixedDiscount() {
        BigDecimal discount = voucherService.calculateDiscountAmount(fixedVoucher, BigDecimal.valueOf(400.00));
        assertThat(discount).isEqualByComparingTo(BigDecimal.valueOf(50.00));
    }

    @Test
    @DisplayName("Percentage discount: calculates percentage correctly")
    void shouldCalculatePercentageDiscount() {
        BigDecimal discount = voucherService.calculateDiscountAmount(percentVoucher, BigDecimal.valueOf(800.00));
        // 10% of 800 = 80
        assertThat(discount).isEqualByComparingTo(BigDecimal.valueOf(80.00));
    }

    @Test
    @DisplayName("Percentage discount: caps at maxDiscountAmount")
    void shouldCapPercentageDiscountAtMax() {
        // 10% of 2,000 = 200, but cap is 100
        BigDecimal discount = voucherService.calculateDiscountAmount(percentVoucher, BigDecimal.valueOf(2000.00));
        assertThat(discount).isEqualByComparingTo(BigDecimal.valueOf(100.00));
    }

    @Test
    @DisplayName("Validation: rejects when subtotal is below min_spend")
    void shouldRejectWhenBelowMinSpend() {
        when(voucherRepository.findByCodeIgnoreCase("MYTHIX50")).thenReturn(Optional.of(fixedVoucher));

        VoucherValidateResponse result = voucherService.validateAndCalculate("MYTHIX50", 1L, BigDecimal.valueOf(200.00));
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("ยอดสั่งซื้อขั้นต่ำ");
    }

    @Test
    @DisplayName("Validation: succeeds when conditions met")
    void shouldValidateSuccessfully() {
        when(voucherRepository.findByCodeIgnoreCase("MYTHIX50")).thenReturn(Optional.of(fixedVoucher));

        VoucherValidateResponse result = voucherService.validateAndCalculate("MYTHIX50", 1L, BigDecimal.valueOf(500.00));
        assertThat(result.valid()).isTrue();
        assertThat(result.discountAmount()).isEqualByComparingTo(BigDecimal.valueOf(50.00));
        assertThat(result.finalTotal()).isEqualByComparingTo(BigDecimal.valueOf(450.00));
    }

    @Test
    @DisplayName("Claim: prevents duplicate claiming by same user")
    void shouldPreventDuplicateClaim() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(voucherRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixedVoucher));
        when(userVoucherRepository.existsByUserIdAndVoucherId(1L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> voucherService.claimVoucher(1L, 1L))
            .isInstanceOf(VoucherExceptions.VoucherAlreadyClaimedException.class);
    }

    @Test
    @DisplayName("Apply to order: marks user voucher as USED and increments usedQuota")
    void shouldApplyVoucherToOrder() {
        when(voucherRepository.findByCodeForUpdate("MYTHIX50")).thenReturn(Optional.of(fixedVoucher));

        UserVoucher uv = UserVoucher.builder()
            .user(user)
            .voucher(fixedVoucher)
            .status("CLAIMED")
            .build();
        when(userVoucherRepository.findByUserIdAndVoucherId(1L, 1L)).thenReturn(Optional.of(uv));

        Order order = Order.builder().id(99L).build();
        BigDecimal discount = voucherService.applyVoucherToOrder("MYTHIX50", user, BigDecimal.valueOf(600.00), order);

        assertThat(discount).isEqualByComparingTo(BigDecimal.valueOf(50.00));
        assertThat(fixedVoucher.getUsedQuota()).isEqualTo(6);
        assertThat(uv.getStatus()).isEqualTo("USED");
        assertThat(order.getVoucher()).isEqualTo(fixedVoucher);
        assertThat(order.getVoucherCodeSnapshot()).isEqualTo("MYTHIX50");
    }

    @Test
    @DisplayName("Release voucher: restores user voucher and decrements usedQuota when order cancelled")
    void shouldReleaseVoucherWhenOrderCancelled() {
        Order order = Order.builder()
            .id(99L)
            .buyer(user)
            .voucher(fixedVoucher)
            .build();

        when(voucherRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixedVoucher));

        UserVoucher uv = UserVoucher.builder()
            .user(user)
            .voucher(fixedVoucher)
            .status("USED")
            .order(order)
            .build();
        when(userVoucherRepository.findByUserIdAndVoucherId(1L, 1L)).thenReturn(Optional.of(uv));

        voucherService.releaseVoucherFromOrder(order);

        assertThat(fixedVoucher.getUsedQuota()).isEqualTo(4);
        assertThat(uv.getStatus()).isEqualTo("CLAIMED");
        assertThat(uv.getOrder()).isNull();
    }
}

package com.mythix.tcg.voucher.application;

import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.voucher.api.VoucherDto.*;
import com.mythix.tcg.voucher.application.VoucherExceptions.*;
import com.mythix.tcg.voucher.domain.UserVoucher;
import com.mythix.tcg.voucher.domain.Voucher;
import com.mythix.tcg.voucher.persistence.UserVoucherRepository;
import com.mythix.tcg.voucher.persistence.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherService {

    private final VoucherRepository voucherRepository;
    private final UserVoucherRepository userVoucherRepository;
    private final UserRepository userRepository;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

    @Transactional(readOnly = true)
    public List<VoucherResponse> getAllAvailableVouchers(Long userId) {
        List<Voucher> activeVouchers = voucherRepository.findAllByIsActiveTrueOrderByCreatedAtDesc();
        LocalDateTime now = LocalDateTime.now();

        Set<Long> claimedVoucherIds = userId == null ? Set.of() :
            userVoucherRepository.findAllByUserId(userId).stream()
                .map(uv -> uv.getVoucher().getId())
                .collect(Collectors.toSet());

        return activeVouchers.stream()
            .filter(v -> (v.getStartTime() == null || !now.isBefore(v.getStartTime()))
                      && (v.getEndTime() == null || !now.isAfter(v.getEndTime())))
            .map(v -> {
                boolean isClaimed = claimedVoucherIds.contains(v.getId());
                int remaining = Math.max(0, v.getTotalQuota() - v.getUsedQuota());
                int percentage = v.getTotalQuota() == 0 ? 0 :
                    (int) Math.round(((double) v.getUsedQuota() / v.getTotalQuota()) * 100);
                boolean canClaim = !isClaimed && remaining > 0;

                return new VoucherResponse(
                    v.getId(),
                    v.getCode(),
                    v.getTitle(),
                    v.getDescription(),
                    v.getDiscountType(),
                    v.getDiscountValue(),
                    v.getMinSpend(),
                    v.getMaxDiscountAmount(),
                    v.getTotalQuota(),
                    v.getUsedQuota(),
                    remaining,
                    percentage,
                    v.getStartTime() != null ? v.getStartTime().format(ISO_FORMATTER) : null,
                    v.getEndTime() != null ? v.getEndTime().format(ISO_FORMATTER) : null,
                    v.getIsActive(),
                    isClaimed,
                    canClaim
                );
            })
            .toList();
    }

    @Transactional
    public UserVoucherResponse claimVoucher(Long userId, Long voucherId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Voucher voucher = voucherRepository.findByIdForUpdate(voucherId)
            .orElseThrow(() -> new VoucherNotFoundException("Voucher not found with ID: " + voucherId));

        if (!Boolean.TRUE.equals(voucher.getIsActive())) {
            throw new VoucherNotApplicableException("This voucher is no longer active");
        }
        if (voucher.isExpired()) {
            throw new VoucherNotApplicableException("This voucher has already expired");
        }
        if (!voucher.isStarted()) {
            throw new VoucherNotApplicableException("This voucher campaign has not started yet");
        }
        if (!voucher.hasQuotaAvailable()) {
            throw new VoucherQuotaExhaustedException("Sorry, this voucher quota has been fully redeemed");
        }

        if (userVoucherRepository.existsByUserIdAndVoucherId(userId, voucherId)) {
            throw new VoucherAlreadyClaimedException("You have already claimed this voucher");
        }

        UserVoucher userVoucher = UserVoucher.builder()
            .user(user)
            .voucher(voucher)
            .status("CLAIMED")
            .build();

        UserVoucher saved = userVoucherRepository.save(userVoucher);
        return toUserVoucherResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<UserVoucherResponse> getUserVouchers(Long userId, String status) {
        List<UserVoucher> list;
        if (status != null && !status.isBlank()) {
            list = userVoucherRepository.findAllByUserIdAndStatusOrderByClaimedAtDesc(userId, status.toUpperCase());
        } else {
            list = userVoucherRepository.findAllByUserIdOrderByClaimedAtDesc(userId);
        }
        return list.stream().map(this::toUserVoucherResponse).toList();
    }

    @Transactional(readOnly = true)
    public VoucherValidateResponse validateAndCalculate(String code, Long userId, BigDecimal subtotal) {
        if (code == null || code.trim().isEmpty()) {
            return new VoucherValidateResponse(false, null, null, null, BigDecimal.ZERO, subtotal, subtotal, "Code is required");
        }
        String cleanCode = code.trim().toUpperCase();
        Voucher voucher = voucherRepository.findByCodeIgnoreCase(cleanCode)
            .orElse(null);

        if (voucher == null) {
            return new VoucherValidateResponse(false, cleanCode, null, null, BigDecimal.ZERO, subtotal, subtotal, "ไม่พบโค้ดส่วนลดนี้");
        }

        if (!Boolean.TRUE.equals(voucher.getIsActive()) || voucher.isExpired()) {
            return new VoucherValidateResponse(false, cleanCode, voucher.getDiscountType(), voucher.getDiscountValue(),
                BigDecimal.ZERO, subtotal, subtotal, "โค้ดส่วนลดนี้หมดอายุแล้ว");
        }

        if (!voucher.isStarted()) {
            return new VoucherValidateResponse(false, cleanCode, voucher.getDiscountType(), voucher.getDiscountValue(),
                BigDecimal.ZERO, subtotal, subtotal, "แคมเปญโค้ดนี้ยังไม่เริ่มต้น");
        }

        if (!voucher.hasQuotaAvailable()) {
            return new VoucherValidateResponse(false, cleanCode, voucher.getDiscountType(), voucher.getDiscountValue(),
                BigDecimal.ZERO, subtotal, subtotal, "โค้ดส่วนลดนี้ถูกใช้สิทธิ์เต็มแล้ว");
        }

        if (subtotal != null && voucher.getMinSpend() != null && subtotal.compareTo(voucher.getMinSpend()) < 0) {
            BigDecimal diff = voucher.getMinSpend().subtract(subtotal);
            return new VoucherValidateResponse(false, cleanCode, voucher.getDiscountType(), voucher.getDiscountValue(),
                BigDecimal.ZERO, subtotal, subtotal,
                String.format("ยอดสั่งซื้อขั้นต่ำ ฿%.2f (ซื้อเพิ่มอีก ฿%.2f เพื่อใช้โค้ดนี้)", voucher.getMinSpend(), diff));
        }

        BigDecimal discount = calculateDiscountAmount(voucher, subtotal);
        BigDecimal finalTotal = subtotal == null ? BigDecimal.ZERO : subtotal.subtract(discount).max(BigDecimal.ZERO);

        return new VoucherValidateResponse(
            true,
            voucher.getCode(),
            voucher.getDiscountType(),
            voucher.getDiscountValue(),
            discount,
            subtotal,
            finalTotal,
            "สามารถใช้โค้ดส่วนลดได้ ลดทันที ฿" + discount.setScale(2, RoundingMode.HALF_UP)
        );
    }

    public BigDecimal calculateDiscountAmount(Voucher voucher, BigDecimal subtotal) {
        if (subtotal == null || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal discount = BigDecimal.ZERO;
        if ("FIXED_AMOUNT".equalsIgnoreCase(voucher.getDiscountType())) {
            discount = voucher.getDiscountValue();
        } else if ("PERCENTAGE".equalsIgnoreCase(voucher.getDiscountType())) {
            BigDecimal rate = voucher.getDiscountValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            discount = subtotal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            if (voucher.getMaxDiscountAmount() != null && discount.compareTo(voucher.getMaxDiscountAmount()) > 0) {
                discount = voucher.getMaxDiscountAmount();
            }
        }
        return discount.min(subtotal);
    }

    @Transactional
    public BigDecimal applyVoucherToOrder(String code, User buyer, BigDecimal subtotal, Order order) {
        if (code == null || code.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        String cleanCode = code.trim().toUpperCase();
        Voucher voucher = voucherRepository.findByCodeForUpdate(cleanCode)
            .orElseThrow(() -> new VoucherNotFoundException("ไม่พบโค้ดส่วนลด: " + cleanCode));

        if (!Boolean.TRUE.equals(voucher.getIsActive()) || voucher.isExpired() || !voucher.isStarted()) {
            throw new VoucherNotApplicableException("โค้ดส่วนลดนี้ไม่สามารถใช้งานได้หรือหมดอายุแล้ว");
        }

        if (!voucher.hasQuotaAvailable()) {
            throw new VoucherQuotaExhaustedException("โค้ดส่วนลดนี้ถูกใช้สิทธิ์เต็มแล้ว");
        }

        if (voucher.getMinSpend() != null && subtotal.compareTo(voucher.getMinSpend()) < 0) {
            throw new VoucherNotApplicableException(
                String.format("ยอดสั่งซื้อขั้นต่ำไม่ถึงเกณฑ์ที่กำหนด (ขั้นต่ำ ฿%.2f)", voucher.getMinSpend()));
        }

        UserVoucher userVoucher = userVoucherRepository.findByUserIdAndVoucherId(buyer.getId(), voucher.getId())
            .orElseGet(() -> {
                // Auto-claim if not already in wallet
                UserVoucher uv = UserVoucher.builder()
                    .user(buyer)
                    .voucher(voucher)
                    .status("CLAIMED")
                    .build();
                return userVoucherRepository.save(uv);
            });

        if ("USED".equalsIgnoreCase(userVoucher.getStatus())) {
            throw new VoucherAlreadyClaimedException("คุณได้ใช้โค้ดส่วนลดนี้ไปแล้ว");
        }

        BigDecimal discount = calculateDiscountAmount(voucher, subtotal);

        // Mark user voucher as used
        userVoucher.setStatus("USED");
        userVoucher.setUsedAt(LocalDateTime.now());
        userVoucher.setOrder(order);

        // Increment quota
        voucher.setUsedQuota(voucher.getUsedQuota() + 1);

        // Link with order
        order.setVoucher(voucher);
        order.setVoucherCodeSnapshot(voucher.getCode());
        order.setDiscountAmount(discount);

        return discount;
    }

    @Transactional
    public void releaseVoucherFromOrder(Order order) {
        if (order.getVoucher() == null) {
            return;
        }
        Voucher voucher = voucherRepository.findByIdForUpdate(order.getVoucher().getId()).orElse(null);
        if (voucher != null && voucher.getUsedQuota() > 0) {
            voucher.setUsedQuota(voucher.getUsedQuota() - 1);
        }

        if (order.getBuyer() != null && voucher != null) {
            userVoucherRepository.findByUserIdAndVoucherId(order.getBuyer().getId(), voucher.getId())
                .ifPresent(uv -> {
                    if ("USED".equalsIgnoreCase(uv.getStatus()) && order.equals(uv.getOrder())) {
                        uv.setStatus("CLAIMED");
                        uv.setUsedAt(null);
                        uv.setOrder(null);
                    }
                });
        }
    }

    private UserVoucherResponse toUserVoucherResponse(UserVoucher uv) {
        Voucher v = uv.getVoucher();
        return new UserVoucherResponse(
            uv.getId(),
            v.getId(),
            v.getCode(),
            v.getTitle(),
            v.getDescription(),
            v.getDiscountType(),
            v.getDiscountValue(),
            v.getMinSpend(),
            v.getMaxDiscountAmount(),
            uv.getStatus(),
            uv.getClaimedAt() != null ? uv.getClaimedAt().format(ISO_FORMATTER) : null,
            uv.getUsedAt() != null ? uv.getUsedAt().format(ISO_FORMATTER) : null,
            v.getEndTime() != null ? v.getEndTime().format(ISO_FORMATTER) : null
        );
    }
}

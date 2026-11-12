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
import com.mythix.tcg.voucher.application.VoucherService;
import com.mythix.tcg.voucher.domain.Voucher;
import com.mythix.tcg.voucher.persistence.UserVoucherRepository;
import com.mythix.tcg.voucher.persistence.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AdminVoucherService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9_-]{2,50}$");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");

    private final VoucherRepository voucherRepository;
    private final UserVoucherRepository userVoucherRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<AdminVoucherResponse> listVouchers(String search, String status, String discountType) {
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = normalizeFilter(status);
        String normalizedType = normalizeFilter(discountType);

        Map<Long, Long> claimedByVoucher = toCountMap(userVoucherRepository.countGroupedByVoucherId());
        Map<Long, Long> ordersByVoucher = toCountMap(orderRepository.countOrdersGroupedByVoucherId());

        return voucherRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
            .filter(v -> matchesSearch(v, normalizedSearch))
            .filter(v -> normalizedType == null || normalizedType.equalsIgnoreCase(v.getDiscountType()))
            .map(v -> toAdminResponse(
                v,
                claimedByVoucher.getOrDefault(v.getId(), 0L),
                ordersByVoucher.getOrDefault(v.getId(), 0L)
            ))
            .filter(resp -> normalizedStatus == null || normalizedStatus.equalsIgnoreCase(resp.effectiveStatus()))
            .toList();
    }

    @Transactional(readOnly = true)
    public AdminVoucherResponse getVoucher(Long id) {
        Voucher voucher = voucherRepository.findById(id)
            .orElseThrow(() -> new VoucherNotFoundException("Voucher not found with ID: " + id));
        long claimedCount = userVoucherRepository.countByVoucherId(id);
        long usedOrdersCount = orderRepository.countByVoucherId(id);
        return toAdminResponse(voucher, claimedCount, usedOrdersCount);
    }

    @Transactional
    public AdminVoucherResponse createVoucher(Long adminUserId, AdminVoucherRequest request) {
        ValidatedVoucher valid = validateRequest(request, 0, true);

        if (voucherRepository.existsByCodeIgnoreCase(valid.code())) {
            throw new VoucherCodeAlreadyExistsException("Voucher code '" + valid.code() + "' already exists");
        }

        Voucher voucher = Voucher.builder()
            .code(valid.code())
            .title(valid.title())
            .description(valid.description())
            .discountType(valid.discountType())
            .discountValue(valid.discountValue())
            .minSpend(valid.minSpend())
            .maxDiscountAmount(valid.maxDiscountAmount())
            .totalQuota(valid.totalQuota())
            .usedQuota(0)
            .perUserLimit(1)
            .startTime(valid.startTime())
            .endTime(valid.endTime())
            .isActive(valid.isActive())
            .build();

        Voucher saved;
        try {
            saved = voucherRepository.saveAndFlush(voucher);
        } catch (DataIntegrityViolationException ex) {
            throw new VoucherCodeAlreadyExistsException("Voucher code '" + valid.code() + "' already exists");
        }

        recordAudit(
            adminUserId,
            "CREATE_VOUCHER",
            saved.getId(),
            String.format(
                Locale.ROOT,
                "Created voucher %s (%s %s, minSpend %s, quota %d, active=%s)",
                saved.getCode(),
                saved.getDiscountType(),
                saved.getDiscountValue(),
                saved.getMinSpend(),
                saved.getTotalQuota(),
                saved.getIsActive()
            )
        );
        publishChange("CREATED", saved.getId());
        return toAdminResponse(saved, 0L, 0L);
    }

    @Transactional
    public AdminVoucherResponse updateVoucher(Long adminUserId, Long id, AdminVoucherRequest request) {
        Voucher voucher = voucherRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new VoucherNotFoundException("Voucher not found with ID: " + id));

        int minQuota = voucher.getUsedQuota() == null ? 0 : voucher.getUsedQuota();
        boolean fallbackActive = Boolean.TRUE.equals(voucher.getIsActive());
        ValidatedVoucher valid = validateRequest(request, minQuota, fallbackActive);

        if (voucherRepository.existsByCodeIgnoreCaseAndIdNot(valid.code(), id)) {
            throw new VoucherCodeAlreadyExistsException("Voucher code '" + valid.code() + "' already exists");
        }

        voucher.setCode(valid.code());
        voucher.setTitle(valid.title());
        voucher.setDescription(valid.description());
        voucher.setDiscountType(valid.discountType());
        voucher.setDiscountValue(valid.discountValue());
        voucher.setMinSpend(valid.minSpend());
        voucher.setMaxDiscountAmount(valid.maxDiscountAmount());
        voucher.setTotalQuota(valid.totalQuota());
        voucher.setStartTime(valid.startTime());
        voucher.setEndTime(valid.endTime());
        voucher.setIsActive(valid.isActive());

        Voucher saved;
        try {
            saved = voucherRepository.saveAndFlush(voucher);
        } catch (DataIntegrityViolationException ex) {
            throw new VoucherCodeAlreadyExistsException("Voucher code '" + valid.code() + "' already exists");
        }

        recordAudit(
            adminUserId,
            "UPDATE_VOUCHER",
            saved.getId(),
            String.format(
                Locale.ROOT,
                "Updated voucher %s (%s %s, minSpend %s, quota %d, active=%s)",
                saved.getCode(),
                saved.getDiscountType(),
                saved.getDiscountValue(),
                saved.getMinSpend(),
                saved.getTotalQuota(),
                saved.getIsActive()
            )
        );
        publishChange("UPDATED", saved.getId());

        long claimedCount = userVoucherRepository.countByVoucherId(saved.getId());
        long usedOrdersCount = orderRepository.countByVoucherId(saved.getId());
        return toAdminResponse(saved, claimedCount, usedOrdersCount);
    }

    @Transactional
    public AdminVoucherResponse toggleVoucherStatus(Long adminUserId, Long id, Boolean isActive) {
        if (isActive == null) {
            throw new VoucherValidationException("isActive", "isActive is required");
        }
        Voucher voucher = voucherRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new VoucherNotFoundException("Voucher not found with ID: " + id));

        voucher.setIsActive(isActive);
        Voucher saved = voucherRepository.saveAndFlush(voucher);

        recordAudit(
            adminUserId,
            "TOGGLE_VOUCHER",
            saved.getId(),
            (Boolean.TRUE.equals(isActive) ? "Enabled voucher " : "Disabled voucher ") + saved.getCode()
        );
        publishChange("TOGGLED", saved.getId());

        long claimedCount = userVoucherRepository.countByVoucherId(saved.getId());
        long usedOrdersCount = orderRepository.countByVoucherId(saved.getId());
        return toAdminResponse(saved, claimedCount, usedOrdersCount);
    }

    @Transactional
    public void deleteVoucher(Long adminUserId, Long id) {
        Voucher voucher = voucherRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new VoucherNotFoundException("Voucher not found with ID: " + id));

        String code = voucher.getCode();
        String title = voucher.getTitle();

        int detachedOrders = orderRepository.detachVoucherByVoucherId(id);
        int removedWalletClaims = userVoucherRepository.deleteAllByVoucherId(id);
        voucherRepository.deleteById(id);
        voucherRepository.flush();

        recordAudit(
            adminUserId,
            "DELETE_VOUCHER",
            id,
            String.format(
                Locale.ROOT,
                "Deleted voucher %s (%s) — detached %d orders, removed %d wallet claims",
                code,
                title,
                detachedOrders,
                removedWalletClaims
            )
        );
        publishChange("DELETED", id);
    }

    private ValidatedVoucher validateRequest(AdminVoucherRequest request, int minQuota, boolean fallbackActive) {
        if (request == null) {
            throw new VoucherValidationException("request", "Request body is required");
        }

        Map<String, String> errors = new LinkedHashMap<>();

        String code = request.code() == null ? "" : request.code().trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            errors.put("code", "Voucher code is required");
        } else if (!CODE_PATTERN.matcher(code).matches()) {
            errors.put("code", "Voucher code must be 2–50 characters using A-Z, 0-9, '-' or '_'");
        }

        String title = request.title() == null ? "" : request.title().trim();
        if (title.isEmpty()) {
            errors.put("title", "Voucher title is required");
        } else if (title.length() > 255) {
            errors.put("title", "Voucher title must not exceed 255 characters");
        }

        String description = request.description() == null ? "" : request.description().trim();

        String discountType = request.discountType() == null
            ? ""
            : request.discountType().trim().toUpperCase(Locale.ROOT);
        if (!"PERCENTAGE".equals(discountType) && !"FIXED_AMOUNT".equals(discountType)) {
            errors.put("discountType", "discountType must be PERCENTAGE or FIXED_AMOUNT");
        }

        BigDecimal discountValue = request.discountValue();
        if (discountValue == null || discountValue.compareTo(BigDecimal.ZERO) <= 0) {
            errors.put("discountValue", "Discount value must be greater than 0");
        } else if (discountValue.compareTo(MAX_MONEY) > 0) {
            errors.put("discountValue", "Discount value exceeds maximum allowed amount");
        } else if ("PERCENTAGE".equals(discountType) && discountValue.compareTo(ONE_HUNDRED) > 0) {
            errors.put("discountValue", "Percentage discount cannot exceed 100%");
        } else {
            discountValue = discountValue.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal minSpend = request.minSpend() == null ? BigDecimal.ZERO : request.minSpend();
        if (minSpend.compareTo(BigDecimal.ZERO) < 0) {
            errors.put("minSpend", "Minimum spend cannot be negative");
        } else if (minSpend.compareTo(MAX_MONEY) > 0) {
            errors.put("minSpend", "Minimum spend exceeds maximum allowed amount");
        } else {
            minSpend = minSpend.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal maxDiscountAmount = null;
        if ("PERCENTAGE".equals(discountType) && request.maxDiscountAmount() != null) {
            if (request.maxDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
                if (request.maxDiscountAmount().compareTo(MAX_MONEY) > 0) {
                    errors.put("maxDiscountAmount", "Max discount cap exceeds maximum allowed amount");
                } else {
                    maxDiscountAmount = request.maxDiscountAmount().setScale(2, RoundingMode.HALF_UP);
                }
            }
        }

        Integer totalQuota = request.totalQuota();
        if (totalQuota == null || totalQuota < 1) {
            errors.put("totalQuota", "Total quota must be at least 1");
        } else if (totalQuota < minQuota) {
            errors.put("totalQuota", "Total quota cannot be less than already used quota (" + minQuota + ")");
        }

        LocalDateTime startTime = null;
        LocalDateTime endTime = null;
        try {
            startTime = VoucherService.parseIsoDateTime(request.startTime(), "startTime");
        } catch (VoucherValidationException ex) {
            errors.put("startTime", ex.getMessage());
        }
        try {
            endTime = VoucherService.parseIsoDateTime(request.endTime(), "endTime");
        } catch (VoucherValidationException ex) {
            errors.put("endTime", ex.getMessage());
        }

        if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
            errors.put("endTime", "End time must be after start time");
        }

        if (!errors.isEmpty()) {
            String summary = errors.values().iterator().next();
            throw new VoucherValidationException(summary, errors);
        }

        boolean isActive = request.isActive() != null ? request.isActive() : fallbackActive;

        return new ValidatedVoucher(
            code,
            title,
            description,
            discountType,
            discountValue,
            minSpend,
            maxDiscountAmount,
            totalQuota,
            startTime,
            endTime,
            isActive
        );
    }

    private AdminVoucherResponse toAdminResponse(Voucher v, long claimedCount, long usedOrdersCount) {
        int totalQuota = v.getTotalQuota() == null ? 0 : v.getTotalQuota();
        int usedQuota = v.getUsedQuota() == null ? 0 : v.getUsedQuota();
        int remaining = Math.max(0, totalQuota - usedQuota);
        int percentage = totalQuota <= 0
            ? 0
            : (int) Math.min(100, Math.round(((double) usedQuota / totalQuota) * 100));

        return new AdminVoucherResponse(
            v.getId(),
            v.getCode(),
            v.getTitle(),
            v.getDescription(),
            v.getDiscountType(),
            v.getDiscountValue(),
            v.getMinSpend(),
            v.getMaxDiscountAmount(),
            totalQuota,
            usedQuota,
            remaining,
            percentage,
            VoucherService.formatIsoOffset(v.getStartTime()),
            VoucherService.formatIsoOffset(v.getEndTime()),
            Boolean.TRUE.equals(v.getIsActive()),
            computeEffectiveStatus(v),
            claimedCount,
            usedOrdersCount,
            VoucherService.formatIsoOffset(v.getCreatedAt()),
            VoucherService.formatIsoOffset(v.getUpdatedAt())
        );
    }

    private String computeEffectiveStatus(Voucher v) {
        if (!Boolean.TRUE.equals(v.getIsActive())) {
            return "INACTIVE";
        }
        if (v.isExpired()) {
            return "EXPIRED";
        }
        if (!v.isStarted()) {
            return "SCHEDULED";
        }
        if (!v.hasQuotaAvailable()) {
            return "EXHAUSTED";
        }
        return "ACTIVE";
    }

    private boolean matchesSearch(Voucher v, String search) {
        if (search.isEmpty()) {
            return true;
        }
        return containsIgnoreCase(v.getCode(), search)
            || containsIgnoreCase(v.getTitle(), search)
            || containsIgnoreCase(v.getDescription(), search);
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String normalizeFilter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "ALL".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        if (rows == null) {
            return map;
        }
        for (Object[] row : rows) {
            if (row != null && row.length >= 2 && row[0] instanceof Number id && row[1] instanceof Number count) {
                map.put(id.longValue(), count.longValue());
            }
        }
        return map;
    }

    private void recordAudit(Long adminUserId, String action, Long voucherId, String details) {
        User actor = adminUserId == null ? null : userRepository.findById(adminUserId).orElse(null);
        auditLogRepository.save(AuditLog.builder()
            .actor(actor)
            .action(action)
            .targetType("VOUCHER")
            .targetId(String.valueOf(voucherId))
            .details(details)
            .build());
    }

    private void publishChange(String eventType, Long voucherId) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new VoucherLiveUpdates.Changed(eventType, voucherId));
        }
    }

    private record ValidatedVoucher(
        String code,
        String title,
        String description,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minSpend,
        BigDecimal maxDiscountAmount,
        Integer totalQuota,
        LocalDateTime startTime,
        LocalDateTime endTime,
        boolean isActive
    ) {}
}

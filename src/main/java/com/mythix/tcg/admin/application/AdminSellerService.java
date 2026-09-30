package com.mythix.tcg.admin.application;

import com.mythix.tcg.admin.api.AdminSellerApplicationResponse;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.admin.api.AdminDashboardResponse;
import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.admin.api.DailyTrend;
import com.mythix.tcg.order.domain.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminSellerService {
    private final SellerProfileRepository sellerProfileRepository;
    private final UserRepository userRepository;
    private final CardRepository cardRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard() {
        LocalDate today = LocalDate.now();
        LocalDateTime sevenDaysAgo = today.minusDays(6).atStartOfDay();
        List<Order> recentOrders = orderRepository.findRecentOrders(sevenDaysAgo);

        Map<LocalDate, List<Order>> ordersByDate = recentOrders.stream()
                .filter(o -> o.getCreatedAt() != null)
                .collect(Collectors.groupingBy(o -> o.getCreatedAt().toLocalDate()));

        List<DailyTrend> trends = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            List<Order> dayOrders = ordersByDate.getOrDefault(date, List.of());
            long count = dayOrders.size();
            BigDecimal gmv = dayOrders.stream()
                    .filter(o -> "PAID".equalsIgnoreCase(o.getStatus()) || "FULFILLED".equalsIgnoreCase(o.getStatus()))
                    .map(Order::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            trends.add(new DailyTrend(date.toString(), gmv, count));
        }

        return new AdminDashboardResponse(
                userRepository.count(),
                userRepository.countByRole("ADMIN"),
                userRepository.countByRoleAndSellerStatus("USER", "NONE"),
                userRepository.countBySellerStatus("PENDING"),
                userRepository.countBySellerStatus("APPROVED"),
                cardRepository.count(),
                productRepository.countByStatus("SHOW"),
                orderRepository.count(),
                orderRepository.paidOrFulfilledGmv(),
                trends
        );
    }

    @Transactional(readOnly = true)
    public List<AdminSellerApplicationResponse> pendingApplications() {
        return sellerProfileRepository.findAllByUserSellerStatusOrderByRequestedAtAsc("PENDING").stream()
            .map(AdminSellerApplicationResponse::from)
            .toList();
    }

    @Transactional
    public AdminSellerApplicationResponse approve(Long applicationId, Long adminUserId) {
        SellerProfile profile = sellerProfileRepository.findById(applicationId)
            .orElseThrow(SellerApplicationNotFoundException::new);
        User applicant = profile.getUser();
        if (!"PENDING".equalsIgnoreCase(applicant.getSellerStatus())) {
            throw new SellerApplicationStateException();
        }
        User admin = userRepository.findById(adminUserId).orElseThrow(SellerApplicationNotFoundException::new);

        applicant.setSellerStatus("APPROVED");
        profile.setApprovedBy(admin.getId());
        profile.setVerifiedAt(LocalDateTime.now());
        auditLogRepository.save(AuditLog.builder().actor(admin).action("APPROVE_SELLER").targetType("SELLER_PROFILE").targetId(String.valueOf(profile.getId())).details("Approved seller application").build());
        return AdminSellerApplicationResponse.from(profile);
    }

    @Transactional
    public AdminSellerApplicationResponse reject(Long applicationId, Long adminUserId, String reason) {
        SellerProfile profile = sellerProfileRepository.findById(applicationId).orElseThrow(SellerApplicationNotFoundException::new);
        if (!"PENDING".equalsIgnoreCase(profile.getUser().getSellerStatus())) throw new SellerApplicationStateException();
        User admin = userRepository.findById(adminUserId).orElseThrow(SellerApplicationNotFoundException::new);
        profile.getUser().setSellerStatus("REJECTED");
        profile.setRejectedBy(admin.getId()); profile.setRejectedAt(LocalDateTime.now()); profile.setRejectionReason(reason.trim());
        auditLogRepository.save(AuditLog.builder().actor(admin).action("REJECT_SELLER").targetType("SELLER_PROFILE").targetId(String.valueOf(profile.getId())).details(reason.trim()).build());
        return AdminSellerApplicationResponse.from(profile);
    }
}

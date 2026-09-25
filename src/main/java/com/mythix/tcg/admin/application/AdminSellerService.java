package com.mythix.tcg.admin.application;

import com.mythix.tcg.admin.api.AdminSellerApplicationResponse;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.admin.api.AdminDashboardResponse;
import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminSellerService {
    private final SellerProfileRepository sellerProfileRepository;
    private final UserRepository userRepository;
    private final CardRepository cardRepository;
    private final OrderRepository orderRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard() {
        return new AdminDashboardResponse(userRepository.count(), userRepository.countByRole("ADMIN"), userRepository.countByRoleAndSellerStatus("USER", "NONE"),
            userRepository.countBySellerStatus("PENDING"), userRepository.countBySellerStatus("APPROVED"),
            cardRepository.count(), orderRepository.count(), orderRepository.paidOrFulfilledGmv());
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
        profile.setApprovedBy(admin);
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
        profile.setRejectedBy(admin); profile.setRejectedAt(LocalDateTime.now()); profile.setRejectionReason(reason.trim());
        auditLogRepository.save(AuditLog.builder().actor(admin).action("REJECT_SELLER").targetType("SELLER_PROFILE").targetId(String.valueOf(profile.getId())).details(reason.trim()).build());
        return AdminSellerApplicationResponse.from(profile);
    }
}

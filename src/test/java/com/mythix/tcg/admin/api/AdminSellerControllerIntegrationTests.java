package com.mythix.tcg.admin.api;

import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.notification.domain.UserNotification;
import com.mythix.tcg.seller.api.SellerVerificationRequest;
import com.mythix.tcg.seller.application.SellerVerificationService;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminSellerControllerIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private SellerVerificationService sellerVerificationService;
    @PersistenceContext private EntityManager entityManager;

    private User admin;
    private User secondAdmin;
    private User applicant;
    private SellerProfile application;

    @BeforeEach
    void setUp() {
        admin = persistUser("admin@example.com", "ADMIN", "NONE");
        secondAdmin = persistUser("admin2@example.com", "ADMIN", "NONE");
        applicant = persistUser("applicant@example.com", "USER", "PENDING");
        application = SellerProfile.builder().user(applicant).shopName("Pending Vault")
            .requestedAt(LocalDateTime.now()).build();
        entityManager.persist(application);
        entityManager.flush();
        entityManager.persist(submittedNotification(admin));
        entityManager.persist(submittedNotification(secondAdmin));
        entityManager.flush();
    }

    @Test
    void adminCanListAndApprovePendingSellerApplications() throws Exception {
        mockMvc.perform(get("/api/admin/seller-applications"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/seller-applications").header("Authorization", bearer(applicant)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/seller-applications").header("Authorization", bearer(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(String.valueOf(application.getId())))
            .andExpect(jsonPath("$[0].sellerStatus").value("PENDING"));

        mockMvc.perform(patch("/api/admin/seller-applications/{id}/approve", application.getId())
                .header("Authorization", bearer(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sellerStatus").value("APPROVED"));

        entityManager.flush();
        entityManager.refresh(applicant);
        entityManager.refresh(application);
        org.assertj.core.api.Assertions.assertThat(applicant.getRole()).isEqualTo("USER");
        org.assertj.core.api.Assertions.assertThat(applicant.getSellerStatus()).isEqualTo("APPROVED");
        org.assertj.core.api.Assertions.assertThat(application.getApprovedBy()).isEqualTo(admin.getId());
        org.assertj.core.api.Assertions.assertThat(countNotifications(NotificationTypes.SELLER_APPLICATION_APPROVED)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(countUnresolvedAdminNotifications()).isZero();

        mockMvc.perform(patch("/api/admin/seller-applications/{id}/reject", application.getId())
                .header("Authorization", bearer(admin))
                .contentType("application/json")
                .content("{\"reason\":\"conflicting decision\"}"))
            .andExpect(status().isConflict());
        entityManager.flush();
        org.assertj.core.api.Assertions.assertThat(countNotifications(NotificationTypes.SELLER_APPLICATION_APPROVED)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(countNotifications(NotificationTypes.SELLER_APPLICATION_REJECTED)).isZero();
    }

    @Test
    void adminRejectionNotifiesApplicantAndResolvesEveryAdminReviewAlert() throws Exception {
        mockMvc.perform(patch("/api/admin/seller-applications/{id}/reject", application.getId())
                .header("Authorization", bearer(admin))
                .contentType("application/json")
                .content("{\"reason\":\"Documents are unclear\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sellerStatus").value("REJECTED"));

        entityManager.flush();
        org.assertj.core.api.Assertions.assertThat(countNotifications(NotificationTypes.SELLER_APPLICATION_REJECTED)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(countUnresolvedAdminNotifications()).isZero();

        mockMvc.perform(patch("/api/admin/seller-applications/{id}/approve", application.getId())
                .header("Authorization", bearer(admin)))
            .andExpect(status().isConflict());
        entityManager.flush();
        org.assertj.core.api.Assertions.assertThat(countNotifications(NotificationTypes.SELLER_APPLICATION_REJECTED)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(countNotifications(NotificationTypes.SELLER_APPLICATION_APPROVED)).isZero();
    }

    @Test
    void sellerSubmissionCreatesOneReviewNotificationForEveryAdmin() {
        User newSeller = persistUser("new-seller@example.com", "USER", "NONE");

        var response = sellerVerificationService.verify(newSeller.getId(),
            new SellerVerificationRequest("New Shop", "Cards", "080-123-4567", null, null));

        org.assertj.core.api.Assertions.assertThat(response.sellerStatus()).isEqualTo("PENDING");
        org.assertj.core.api.Assertions.assertThat(entityManager.createQuery(
                "select count(n) from UserNotification n where n.sellerProfile.id = :profileId and n.type = :type", Long.class)
            .setParameter("profileId", Long.valueOf(response.id()))
            .setParameter("type", NotificationTypes.SELLER_APPLICATION_SUBMITTED)
            .getSingleResult()).isEqualTo(2L);
    }

    private UserNotification submittedNotification(User recipient) {
        return UserNotification.builder().recipient(recipient).sellerProfile(application)
            .type(NotificationTypes.SELLER_APPLICATION_SUBMITTED).title("New seller application")
            .message("Application submitted").build();
    }

    private long countNotifications(String type) {
        return entityManager.createQuery("select count(n) from UserNotification n where n.sellerProfile.id = :profileId and n.type = :type", Long.class)
            .setParameter("profileId", application.getId()).setParameter("type", type).getSingleResult();
    }

    private long countUnresolvedAdminNotifications() {
        return entityManager.createQuery("select count(n) from UserNotification n where n.sellerProfile.id = :profileId and n.type = :type and n.readAt is null", Long.class)
            .setParameter("profileId", application.getId())
            .setParameter("type", NotificationTypes.SELLER_APPLICATION_SUBMITTED).getSingleResult();
    }

    private User persistUser(String email, String role, String sellerStatus) {
        User user = User.builder().email(email).password("hash").fullName(email)
            .role(role).sellerStatus(sellerStatus).build();
        entityManager.persist(user);
        return user;
    }

    private String bearer(User user) { return "Bearer " + jwtService.generateToken(user); }
}

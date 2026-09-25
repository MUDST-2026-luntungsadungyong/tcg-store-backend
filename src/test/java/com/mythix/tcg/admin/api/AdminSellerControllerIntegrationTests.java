package com.mythix.tcg.admin.api;

import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
    @PersistenceContext private EntityManager entityManager;

    private User admin;
    private User applicant;
    private SellerProfile application;

    @BeforeEach
    void setUp() {
        admin = persistUser("admin@example.com", "ADMIN", "NONE");
        applicant = persistUser("applicant@example.com", "USER", "PENDING");
        application = SellerProfile.builder().user(applicant).shopName("Pending Vault")
            .requestedAt(LocalDateTime.now()).build();
        entityManager.persist(application);
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
    }

    private User persistUser(String email, String role, String sellerStatus) {
        User user = User.builder().email(email).password("hash").fullName(email)
            .role(role).sellerStatus(sellerStatus).build();
        entityManager.persist(user);
        return user;
    }

    private String bearer(User user) { return "Bearer " + jwtService.generateToken(user); }
}
